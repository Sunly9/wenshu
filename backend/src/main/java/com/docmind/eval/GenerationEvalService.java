package com.docmind.eval;

import com.docmind.generation.LlmClient;
import com.docmind.generation.PromptBuilder;
import com.docmind.retrieve.RetrievalService;
import com.docmind.retrieve.assembler.ContextAssembler;
import com.docmind.retrieve.recall.RetrievedChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D19 生成质量评测（00 号文档 §8）：
 * 可答题跑完整问答链路 → LLM 裁判逐题审忠实度 + 逐角标审引用支撑；
 * 拒答阈值校准：可答题与无答案题的 top1 精排分对比 → 建议阈值。
 * 结果落 eval_run（citation_accuracy / faithfulness + detail 供人工抽检）。
 */
@Service
public class GenerationEvalService {

    private static final Logger log = LoggerFactory.getLogger(GenerationEvalService.class);

    private static final double REFUSE_THRESHOLD = 0.35;
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

    private static final String JUDGE_SYSTEM = """
            你是严格的评审员。只依据给定的编号资料审查回答，输出合法 JSON：
            {"faithful": true或false, "unsupported": ["回答中没有资料依据的句子"], "citations": [{"n": 1, "supports": true}]}
            faithful = 回答的结论全部有资料依据；citations 覆盖回答中出现的每个 [n] 角标，判断资料块 n 是否真的支撑其标注的那句结论。""";

    private final JdbcTemplate jdbc;
    private final RetrievalService retrievalService;
    private final ContextAssembler assembler;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public GenerationEvalService(JdbcTemplate jdbc, RetrievalService retrievalService,
                                 ContextAssembler assembler, PromptBuilder promptBuilder, LlmClient llmClient) {
        this.jdbc = jdbc;
        this.retrievalService = retrievalService;
        this.assembler = assembler;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
    }

    /** 裁判结果：忠实 + 角标支撑计数 */
    record Verdict(boolean faithful, long supportedCitations, long totalCitations, String answerHead) {}

    @Async("ingestExecutor")
    public void runAsync(long kbId) {
        try {
            run(kbId);
        } catch (Exception e) {
            log.error("生成质量评测失败 kb={}", kbId, e);
        }
    }

    public void run(long kbId) {
        List<Map<String, Object>> questions = jdbc.queryForList(
                "SELECT id, question FROM eval_question WHERE kb_id = ? AND type != 'NO_ANSWER'", kbId);
        List<Map<String, Object>> noAnswer = jdbc.queryForList(
                "SELECT id, question FROM eval_question WHERE kb_id = ? AND type = 'NO_ANSWER'", kbId);

        int faithfulCount = 0;
        int refusedCount = 0;
        long supported = 0;
        long totalCited = 0;
        List<Map<String, Object>> perQuestion = new ArrayList<>();

        for (Map<String, Object> q : questions) {
            String question = (String) q.get("question");
            try {
                RetrievalService.RetrievalResult r = retrievalService.recall(kbId, question);
                boolean refused = r.reranked().isEmpty()
                        || r.reranked().get(0).rerankScore() == null
                        || r.reranked().get(0).rerankScore() < REFUSE_THRESHOLD;
                if (refused) {
                    refusedCount++;
                    perQuestion.add(Map.of("id", q.get("id"), "question", question, "refused", true));
                    continue;
                }
                List<RetrievedChunk> context = assembler.assembleContext(r.reranked());
                String prompt = promptBuilder.buildUserPrompt(question, context);
                String answer = llmClient.complete(PromptBuilder.SYSTEM_PROMPT, prompt, false, 0.1);
                Verdict v = judge(question, context, answer);
                if (v.faithful()) faithfulCount++;
                supported += v.supportedCitations();
                totalCited += v.totalCitations();
                perQuestion.add(Map.of(
                        "id", q.get("id"), "question", question, "refused", false,
                        "faithful", v.faithful(), "citations", v.totalCitations(),
                        "answer", v.answerHead()));
            } catch (Exception e) {
                log.warn("题目评测失败: {} -> {}", question, e.getMessage());
            }
        }

        Map<String, Object> calibration = calibrate(kbId, questions, noAnswer);

        Map<String, Object> detail = new HashMap<>();
        detail.put("perQuestion", perQuestion);
        detail.put("refusedCount", refusedCount);
        detail.put("citationTotal", totalCited);
        detail.put("calibration", calibration);

        double citationAccuracy = totalCited == 0 ? 0 : supported * 1.0 / totalCited;
        double faithfulness = questions.isEmpty() ? 0 : faithfulCount * 1.0 / questions.size();
        jdbc.update("""
                        INSERT INTO eval_run(kb_id, config, hit_rate_at5, mrr, citation_accuracy, faithfulness, detail)
                        VALUES (?, ?::jsonb, NULL, NULL, ?, ?, ?::jsonb)
                        """,
                kbId, "{\"name\":\"⑦ 完整链路生成质量\",\"type\":\"generation\"}",
                citationAccuracy, faithfulness, toJson(detail));
        log.info("生成质量评测完成 kb={} 忠实度={} 引用准确率={}({}/{}角标) 误拒={} 校准={}",
                kbId, faithfulness, citationAccuracy, supported, totalCited, refusedCount, calibration);
    }

    private Verdict judge(String question, List<RetrievedChunk> context, String answer) {
        StringBuilder user = new StringBuilder("问题：").append(question).append("\n\n资料：\n");
        for (int i = 0; i < context.size(); i++) {
            user.append('[').append(i + 1).append("] ").append(context.get(i).content()).append("\n\n");
        }
        user.append("学生的回答：\n").append(answer).append("\n\n请评审。");
        String head = answer.length() > 500 ? answer.substring(0, 500) : answer;
        try {
            String json = llmClient.complete(JUDGE_SYSTEM, user.toString(), true, 0.1);
            JsonNode root = mapper.readTree(json);
            Matcher m = CITATION.matcher(answer);
            long used = m.results().count();
            long sup = 0;
            for (JsonNode c : root.path("citations")) {
                if (c.path("supports").asBoolean(false)) sup++;
            }
            return new Verdict(root.path("faithful").asBoolean(false), sup, used, head);
        } catch (Exception e) {
            return new Verdict(false, 0, countCitations(answer), head);
        }
    }

    private long countCitations(String answer) {
        return CITATION.matcher(answer).results().count();
    }

    /** 拒答阈值校准：两类问题的 top1 精排分分布 → 建议阈值（中点） */
    private Map<String, Object> calibrate(long kbId, List<Map<String, Object>> questions,
                                          List<Map<String, Object>> noAnswer) {
        double minAnswerable = topScores(kbId, questions).stream().mapToDouble(Double::doubleValue).min().orElse(-1);
        double maxNoAnswer = topScores(kbId, noAnswer).stream().mapToDouble(Double::doubleValue).max().orElse(-1);
        double suggested = minAnswerable < 0 || maxNoAnswer < 0 ? REFUSE_THRESHOLD : (minAnswerable + maxNoAnswer) / 2;
        return Map.of(
                "currentThreshold", REFUSE_THRESHOLD,
                "suggestedThreshold", round3(suggested),
                "minAnswerableTopScore", round3(minAnswerable),
                "maxNoAnswerTopScore", round3(maxNoAnswer));
    }

    private List<Double> topScores(long kbId, List<Map<String, Object>> questions) {
        List<Double> scores = new ArrayList<>();
        for (Map<String, Object> q : questions) {
            RetrievalService.RetrievalResult r = retrievalService.recall(kbId, (String) q.get("question"));
            if (!r.reranked().isEmpty() && r.reranked().get(0).rerankScore() != null) {
                scores.add(r.reranked().get(0).rerankScore());
            }
        }
        return scores;
    }

    private double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
