package com.docmind.quiz;

import com.docmind.common.exception.ApiException;
import com.docmind.domain.repo.KnowledgeBaseRepository;
import com.docmind.generation.LlmClient;
import com.docmind.service.KbService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 出题与判分（03 号文档 §7.2）：
 * 出题 = 范围内采样 6 个内容单元 → 一次 LLM 调用生成 5 题（3 单选 + 2 简答，JSON 输出）；
 * 判分 = 单选本地判；简答由 LLM 依据原文打分并摘录漏掉的句子。
 * 无状态：题目随 sourceChunkIds 由前端持有，判分时带回。
 */
@Service
public class QuizService {

    private static final Logger log = LoggerFactory.getLogger(QuizService.class);

    private static final String GEN_SYSTEM = """
            你是严格的课程出题官。只依据给定资料出题，禁止使用资料之外的知识。
            输出必须是合法 JSON：{"questions":[{"type":"single","stem":"题干","options":["A xxx","B xxx","C xxx","D xxx"],"answer":"正确选项字母","explanation":"依据资料的解析","sourceNos":[1]},{"type":"short","stem":"题干","answer":"参考答案要点","explanation":"解析","sourceNos":[2]}]}
            要求：恰好 5 题 = 3 道单选 + 2 道简答；考察概念理解而非死记；每题 sourceNos 标注依据的资料编号。""";

    private static final String JUDGE_SYSTEM = """
            你是严格但鼓励学生的阅卷老师，只依据给定资料判分。
            输出合法 JSON：{"score":0到100整数,"comment":"一句话点评","missedSentences":["从资料原文中逐字摘录学生漏掉的关键句子"]}
            学生答对要点则不列入 missedSentences；宁可宽松也不冤枉，但没有依据的回答不给分。""";

    private final JdbcTemplate jdbc;
    private final KbService kbService;
    private final KnowledgeBaseRepository kbRepo;
    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public QuizService(JdbcTemplate jdbc, KbService kbService, KnowledgeBaseRepository kbRepo, LlmClient llmClient) {
        this.jdbc = jdbc;
        this.kbService = kbService;
        this.kbRepo = kbRepo;
        this.llmClient = llmClient;
    }

    public List<QuizQuestion> generate(long kbId, Long documentId, String sectionPrefix, String visitorId) {
        kbService.requireAccessible(kbId, visitorId);
        String strategy = kbRepo.findById(kbId).map(k -> k.getChunkStrategy()).orElse("STRUCTURE_AWARE");

        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.content FROM chunk c
                JOIN document d ON d.id = c.document_id
                WHERE d.kb_id = ? AND d.status = 'READY' AND c.parent_id IS NULL AND c.strategy = ?
                """);
        List<Object> args = new ArrayList<>(List.of(kbId, strategy));
        if (documentId != null) {
            sql.append(" AND c.document_id = ?");
            args.add(documentId);
        }
        if (sectionPrefix != null && !sectionPrefix.isBlank()) {
            sql.append(" AND c.section_path LIKE ?");
            args.add(sectionPrefix.trim() + "%");
        }
        sql.append(" ORDER BY random() LIMIT 6");
        List<Map<String, Object>> units = jdbc.queryForList(sql.toString(), args.toArray());
        if (units.size() < 2) {
            throw new ApiException("所选范围内的内容太少（" + units.size() + " 个内容单元），换个大一点的范围试试");
        }

        StringBuilder user = new StringBuilder("资料：\n\n");
        for (int i = 0; i < units.size(); i++) {
            String content = String.valueOf(units.get(i).get("content"));
            user.append('[').append(i + 1).append("] ")
                    .append(content, 0, Math.min(content.length(), 1500)).append("\n\n");
        }
        user.append("请依据以上资料生成练习题。");

        String json = llmClient.complete(GEN_SYSTEM, user.toString(), true, 0.3);
        List<QuizQuestion> questions = parseQuestions(json, units);
        log.info("出题完成 kb={} 范围 doc={}/{} → {} 题", kbId, documentId, sectionPrefix, questions.size());
        return questions;
    }

    private List<QuizQuestion> parseQuestions(String json, List<Map<String, Object>> units) {
        try {
            JsonNode root = mapper.readTree(json);
            List<QuizQuestion> questions = new ArrayList<>();
            for (JsonNode q : root.path("questions")) {
                List<Long> chunkIds = new ArrayList<>();
                for (JsonNode n : q.path("sourceNos")) {
                    int idx = n.asInt() - 1;
                    if (idx >= 0 && idx < units.size()) {
                        chunkIds.add(((Number) units.get(idx).get("id")).longValue());
                    }
                }
                List<String> options = new ArrayList<>();
                for (JsonNode o : q.path("options")) options.add(o.asText());
                questions.add(new QuizQuestion(
                        q.path("type").asText("short"),
                        q.path("stem").asText(),
                        options,
                        q.path("answer").asText(),
                        q.path("explanation").asText(),
                        chunkIds));
            }
            if (questions.size() < 3) {
                throw new IllegalStateException("题目数量不足：" + questions.size());
            }
            return questions;
        } catch (Exception e) {
            throw new ApiException("出题失败（模型输出异常，请重试）：" + e.getMessage());
        }
    }

    public List<GradeItem> grade(long kbId, List<QuizQuestion> questions, List<String> userAnswers, String visitorId) {
        kbService.requireAccessible(kbId, visitorId);
        List<GradeItem> results = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            QuizQuestion q = questions.get(i);
            String userAnswer = i < userAnswers.size() ? String.valueOf(userAnswers.get(i)).trim() : "";
            if ("single".equals(q.type())) {
                boolean correct = !userAnswer.isEmpty() && userAnswer.equalsIgnoreCase(String.valueOf(q.answer()).trim());
                results.add(new GradeItem(i, q.type(), correct, correct ? 100 : 0,
                        (correct ? "答对了。" : "正确答案是 " + q.answer() + "。") + defaultString(q.explanation()),
                        List.of()));
            } else {
                results.add(gradeShort(q, userAnswer));
            }
        }
        return results;
    }

    private GradeItem gradeShort(QuizQuestion q, String userAnswer) {
        String source = fetchSourceText(q.sourceChunkIds());
        if (source.isBlank()) source = defaultString(q.explanation());
        String user = "题目：" + q.stem() + "\n\n资料原文：\n" + source
                + "\n\n参考答案要点：" + defaultString(q.answer())
                + "\n\n学生的回答：" + (userAnswer.isBlank() ? "（未作答）" : userAnswer)
                + "\n\n请判分。";
        try {
            String json = llmClient.complete(JUDGE_SYSTEM, user, true, 0.1);
            JsonNode root = mapper.readTree(json);
            List<String> missed = new ArrayList<>();
            for (JsonNode m : root.path("missedSentences")) {
                missed.add(m.asText());
            }
            return new GradeItem(0, "short", null,
                    clamp(root.path("score").asInt(0)),
                    root.path("comment").asText(""),
                    missed);
        } catch (Exception e) {
            return new GradeItem(0, "short", null, 0, "判分服务暂时不可用，请重试", List.of());
        }
    }

    private String fetchSourceText(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return "";
        StringBuilder in = new StringBuilder("(");
        for (int i = 0; i < chunkIds.size(); i++) {
            if (i > 0) in.append(',');
            in.append(chunkIds.get(i));
        }
        in.append(')');
        StringBuilder sb = new StringBuilder();
        jdbc.query("SELECT content FROM chunk WHERE id IN " + in, rs -> {
            sb.append(rs.getString(1)).append("\n\n");
        });
        return sb.length() > 4000 ? sb.substring(0, 4000) : sb.toString();
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private String defaultString(String s) {
        return s == null ? "" : s;
    }
}
