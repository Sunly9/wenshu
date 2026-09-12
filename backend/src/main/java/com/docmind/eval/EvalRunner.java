package com.docmind.eval;

import com.docmind.retrieve.RetrievalService;
import com.docmind.retrieve.fusion.FusedChunk;
import com.docmind.retrieve.recall.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 消融实验跑批（00 号文档 §8，6 配置：逐行只加一个变量）。
 * 指标：HitRate@5 / MRR（43 道可答题，跨策略命中用内容 10-gram 覆盖率匹配）；
 * 无答案题仅在 Rerank 开启的配置下统计正确拒答率（最高精排分 < 0.35）。
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    record AblationConfig(String name, String strategy, boolean useFts, boolean useRerank, boolean parentCredit) {}

    static final List<AblationConfig> CONFIGS = List.of(
            new AblationConfig("① 固定长度+纯向量", "FIXED", false, false, false),
            new AblationConfig("② 递归分隔符+纯向量", "RECURSIVE", false, false, false),
            new AblationConfig("③ 结构感知+纯向量", "STRUCTURE_AWARE", false, false, false),
            new AblationConfig("④ 结构感知+父子分块", "STRUCTURE_AWARE", false, false, true),
            new AblationConfig("⑤ 结构感知+双路召回+RRF", "STRUCTURE_AWARE", true, false, false),
            new AblationConfig("⑥ ⑤+Rerank 精排", "STRUCTURE_AWARE", true, true, false));

    static final double REFUSE_THRESHOLD = 0.35;
    private static final int TOP_K = 5;
    private static final double OVERLAP_MIN_COVERAGE = 0.30;

    private final JdbcTemplate jdbc;
    private final RetrievalService retrievalService;

    public EvalRunner(JdbcTemplate jdbc, RetrievalService retrievalService) {
        this.jdbc = jdbc;
        this.retrievalService = retrievalService;
    }

    @Async("ingestExecutor")
    public void runAsync(long kbId, String visitorId) {
        try {
            run(kbId, visitorId);
        } catch (Exception e) {
            log.error("消融跑批失败 kb={}", kbId, e);
        }
    }

    public void run(long kbId, String visitorId) {
        List<Map<String, Object>> questions = jdbc.queryForList(
                "SELECT id, type, question, gold_chunk_ids FROM eval_question WHERE kb_id = ?", kbId);
        List<Map<String, Object>> answerable = questions.stream()
                .filter(q -> !"NO_ANSWER".equals(q.get("type"))).toList();
        List<Map<String, Object>> noAnswer = questions.stream()
                .filter(q -> "NO_ANSWER".equals(q.get("type"))).toList();
        if (answerable.isEmpty()) {
            log.warn("评测集为空，跳过跑批 kb={}", kbId);
            return;
        }

        // gold 块的 id → (parentId, content)：父子宽限判定 + 跨策略内容匹配
        Set<Long> goldIds = new HashSet<>();
        for (Map<String, Object> q : answerable) {
            for (Long id : parseIds(q.get("gold_chunk_ids"))) goldIds.add(id);
        }
        Map<Long, Long> goldParents = new HashMap<>();
        Map<Long, String> goldContents = new HashMap<>();
        if (!goldIds.isEmpty()) {
            StringBuilder in = new StringBuilder("(");
            goldIds.forEach(id -> in.append(id).append(','));
            in.setLength(in.length() - 1);
            in.append(')');
            jdbc.query("SELECT id, parent_id, content FROM chunk WHERE id IN " + in, rs -> {
                long id = rs.getLong(1);
                long parent = rs.getLong(2);
                if (!rs.wasNull()) goldParents.put(id, parent);
                goldContents.put(id, rs.getString(3));
            });
        }

        for (AblationConfig cfg : CONFIGS) {
            long t0 = System.currentTimeMillis();
            int hits = 0;
            double rrSum = 0;
            Map<String, int[]> typeStats = new HashMap<>();  // type → [hits, total]
            List<Map<String, Object>> perQuestion = new ArrayList<>();
            int refusalCorrect = 0;

            for (Map<String, Object> q : answerable) {
                String question = (String) q.get("question");
                List<Long> gold = parseIds(q.get("gold_chunk_ids"));
                RetrievalService.RetrievalResult r = retrievalService.recall(
                        kbId, question, cfg.strategy(), cfg.useFts(), cfg.useRerank());
                List<FusedChunk> top = r.reranked().size() > TOP_K
                        ? r.reranked().subList(0, TOP_K) : r.reranked();

                int rank = -1;
                for (int i = 0; i < top.size(); i++) {
                    if (isHit(top.get(i).chunk(), cfg, gold, goldParents, goldContents)) {
                        rank = i;
                        break;
                    }
                }
                boolean hit = rank >= 0;
                if (hit) {
                    hits++;
                    rrSum += 1.0 / (rank + 1);
                }
                String type = String.valueOf(q.get("type"));
                int[] st = typeStats.computeIfAbsent(type, k -> new int[2]);
                st[1]++;
                if (hit) st[0]++;
                perQuestion.add(Map.of("id", q.get("id"), "type", type, "hit", hit,
                        "rank", rank + 1));
            }

            // 无答案题：仅 Rerank 配置统计正确拒答
            if (cfg.useRerank()) {
                for (Map<String, Object> q : noAnswer) {
                    RetrievalService.RetrievalResult r = retrievalService.recall(
                            kbId, (String) q.get("question"), cfg.strategy(), true, true);
                    boolean refused = r.reranked().isEmpty()
                            || r.reranked().get(0).rerankScore() == null
                            || r.reranked().get(0).rerankScore() < REFUSE_THRESHOLD;
                    if (refused) refusalCorrect++;
                }
            }

            Map<String, Object> detail = new HashMap<>();
            Map<String, Object> byType = new HashMap<>();
            typeStats.forEach((t, st) -> byType.put(t, Map.of(
                    "hitRate", st[1] == 0 ? 0 : Math.round(st[0] * 10000.0 / st[1]) / 10000.0, "total", st[1])));
            detail.put("byType", byType);
            if (!noAnswer.isEmpty()) {
                detail.put("refusalRate", Math.round(refusalCorrect * 10000.0 / noAnswer.size()) / 10000.0);
            }
            detail.put("questions", perQuestion);
            detail.put("durationMs", System.currentTimeMillis() - t0);

            jdbc.update("""
                            INSERT INTO eval_run(kb_id, config, hit_rate_at5, mrr, detail)
                            VALUES (?, ?::jsonb, ?, ?, ?::jsonb)
                            """,
                    kbId,
                    "{\"name\":\"" + cfg.name() + "\",\"strategy\":\"" + cfg.strategy()
                            + "\",\"useFts\":" + cfg.useFts() + ",\"useRerank\":" + cfg.useRerank()
                            + ",\"parentCredit\":" + cfg.parentCredit() + "}",
                    hits * 1.0 / answerable.size(),
                    rrSum / answerable.size(),
                    toJson(detail));
            log.info("消融配置完成 kb={} {} → HitRate@5={} MRR={} 耗时{}ms",
                    kbId, cfg.name(), hits * 1.0 / answerable.size(), rrSum / answerable.size(),
                    System.currentTimeMillis() - t0);
        }
        log.info("消融跑批全部完成 kb={} 共 {} 配置", kbId, CONFIGS.size());
    }

    private boolean isHit(RetrievedChunk candidate, AblationConfig cfg, List<Long> gold,
                          Map<Long, Long> goldParents, Map<Long, String> goldContents) {
        if (gold.contains(candidate.chunkId())) return true;
        if ("STRUCTURE_AWARE".equals(cfg.strategy())) {
            if (cfg.parentCredit() && candidate.parentChunkId() != null
                    && goldParents.containsValue(candidate.parentChunkId())) {
                return true;
            }
        } else {
            // FIXED/RECURSIVE 块与 gold 不同 id：内容 10-gram 覆盖率匹配同一段原文
            String cand = normalize(candidate.content());
            for (Long gid : gold) {
                String goldText = normalize(goldContents.get(gid));
                if (!goldText.isEmpty() && coverage(shingles(cand), shingles(goldText)) >= OVERLAP_MIN_COVERAGE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static Set<String> shingles(String s) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 10 <= s.length(); i++) {
            set.add(s.substring(i, i + 10));
        }
        return set;
    }

    /** 较小 gram 集被较大集合的覆盖比例（块大小差异大时比 Jaccard 更合理） */
    private static double coverage(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> small = a.size() < b.size() ? a : b;
        Set<String> big = small == a ? b : a;
        int inter = 0;
        for (String s : small) {
            if (big.contains(s)) inter++;
        }
        return (double) inter / small.size();
    }

    private List<Long> parseIds(Object value) {
        if (value == null) return List.of();
        String s = value.toString().replaceAll("[{}\\s\"]", "");
        if (s.isBlank()) return List.of();
        List<Long> ids = new ArrayList<>();
        for (String part : s.split(",")) {
            ids.add(Long.valueOf(part));
        }
        return ids;
    }

    private String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
