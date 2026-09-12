package com.docmind.retrieve;

import com.docmind.retrieve.fusion.FusedChunk;
import com.docmind.retrieve.fusion.RrfFusion;
import com.docmind.retrieve.recall.FtsRecall;
import com.docmind.retrieve.recall.RetrievedChunk;
import com.docmind.retrieve.recall.VectorRecall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检索编排：向量 50 + 关键词 50 → RRF 融合 30 → Rerank 精排留 8（00 号文档 §6 冻结参数）。
 * 每阶段耗时与得分全部保留——检索调试台的数据源。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    static final int RECALL_TOP_K = 50;
    static final int FUSED_LIMIT = 30;
    static final int RERANK_INPUT = 10;  // 只精排 RRF 前 10：CPU 单对约 170ms，全量 30 不可行（D11 实测）
    static final int RERANK_KEEP = 8;

    private final VectorRecall vectorRecall;
    private final FtsRecall ftsRecall;
    private final RrfFusion rrfFusion;
    private final com.docmind.retrieve.rerank.RerankClient rerankClient;
    private final com.docmind.domain.repo.KnowledgeBaseRepository kbRepo;

    public RetrievalService(VectorRecall vectorRecall, FtsRecall ftsRecall, RrfFusion rrfFusion,
                            com.docmind.retrieve.rerank.RerankClient rerankClient,
                            com.docmind.domain.repo.KnowledgeBaseRepository kbRepo) {
        this.vectorRecall = vectorRecall;
        this.ftsRecall = ftsRecall;
        this.rrfFusion = rrfFusion;
        this.rerankClient = rerankClient;
        this.kbRepo = kbRepo;
    }

    public RetrievalResult recall(long kbId, String question) {
        return recall(kbId, question, null, true, true);
    }

    public RetrievalResult recall(long kbId, String question, String strategyOverride) {
        return recall(kbId, question, strategyOverride, true, true);
    }

    /** 消融实验入口：可关掉关键词召回（纯向量）与精排（RRF 序直出） */
    public RetrievalResult recall(long kbId, String question, String strategyOverride,
                                  boolean useFts, boolean useRerank) {
        String strategy = strategyOverride != null ? strategyOverride
                : kbRepo.findById(kbId).map(com.docmind.domain.KnowledgeBase::getChunkStrategy)
                        .orElse("STRUCTURE_AWARE");
        long t0 = System.currentTimeMillis();
        List<RetrievedChunk> byVector = vectorRecall.recall(kbId, question, RECALL_TOP_K, strategy);
        long vectorMs = System.currentTimeMillis() - t0;

        long t1 = System.currentTimeMillis();
        List<RetrievedChunk> byFts = useFts ? ftsRecall.recall(kbId, question, RECALL_TOP_K, strategy) : List.of();
        long ftsMs = System.currentTimeMillis() - t1;

        long t2 = System.currentTimeMillis();
        List<FusedChunk> fused;
        if (useFts) {
            fused = rrfFusion.fuse(byVector, byFts, FUSED_LIMIT);
        } else {
            fused = new ArrayList<>();
            for (int r = 0; r < Math.min(byVector.size(), FUSED_LIMIT); r++) {
                RetrievedChunk c = byVector.get(r);
                fused.add(new FusedChunk(c, c.vectorScore(), null,
                        1.0 / (com.docmind.retrieve.fusion.RrfFusion.K + r + 1), r + 1, null));
            }
        }
        long fuseMs = System.currentTimeMillis() - t2;

        long t3 = System.currentTimeMillis();
        List<FusedChunk> rerankInput = fused.size() > RERANK_INPUT
                ? new ArrayList<>(fused.subList(0, RERANK_INPUT)) : fused;
        List<FusedChunk> scored = useRerank ? rerank(kbId, question, rerankInput) : rerankInput;
        long rerankMs = System.currentTimeMillis() - t3;
        List<FusedChunk> reranked = scored.size() > RERANK_KEEP
                ? new ArrayList<>(scored.subList(0, RERANK_KEEP)) : scored;

        log.info("检索完成 kb={} 策略{} 向量{}条/{}ms 关键词{}条/{}ms 融合{}条/{}ms 精排留{}条/{}ms 最高分{}",
                kbId, strategy, byVector.size(), vectorMs, byFts.size(), ftsMs, fused.size(), fuseMs,
                reranked.size(), rerankMs,
                reranked.isEmpty() ? "-" : String.format("%.3f", reranked.get(0).rerankScore() == null ? -1 : reranked.get(0).rerankScore()));
        return new RetrievalResult(byVector, byFts, fused, scored, reranked, vectorMs, ftsMs, fuseMs, rerankMs);
    }

    /** 精排失败自动降级为纯 RRF 序（03 号文档 §5.1 降级路径），返回带分的完整候选（排序后） */
    private List<FusedChunk> rerank(long kbId, String question, List<FusedChunk> fused) {
        if (fused.isEmpty()) return fused;
        List<FusedChunk> result = new ArrayList<>(fused.size());
        try {
            double[] scores = rerankClient.score(question,
                    fused.stream().map(f -> f.chunk().content()).toList());
            for (int i = 0; i < fused.size(); i++) {
                result.add(fused.get(i).withRerank(scores[i]));
            }
            result.sort((a, b) -> Double.compare(b.rerankScore(), a.rerankScore()));
        } catch (Exception e) {
            log.warn("Rerank 失败，降级为纯 RRF 序：{}", e.getMessage());
            result = new ArrayList<>(fused);
        }
        return result;
    }

    /** 漏斗计数 + 各阶段耗时（query_log.retrieval_meta，调试台数据源） */
    public Map<String, Object> meta(RetrievalResult r) {
        Map<String, Object> meta = new java.util.HashMap<>();
        meta.put("vectorCount", r.vectorRecalled().size());
        meta.put("ftsCount", r.ftsRecalled().size());
        meta.put("fusedCount", r.fused().size());
        meta.put("rerankCount", r.scored().size());
        meta.put("vectorMs", r.vectorMs());
        meta.put("ftsMs", r.ftsMs());
        meta.put("fuseMs", r.fuseMs());
        meta.put("rerankMs", r.rerankMs());
        return meta;
    }

    /** 精排候选明细（含出处与内容摘录，query_log.retrieved） */
    public List<Map<String, Object>> candidatesDetail(RetrievalResult r) {
        List<Map<String, Object>> detail = new ArrayList<>();
        for (FusedChunk f : r.scored()) {
            RetrievedChunk c = f.chunk();
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("chunkId", c.chunkId());
            m.put("file", c.fileName());
            m.put("section", c.sectionPath());
            m.put("page", c.pageNo());
            m.put("snippet", snippet(c.content(), 300));
            m.put("tokenCount", c.tokenCount());
            if (f.vectorScore() != null) m.put("vectorScore", round4(f.vectorScore()));
            if (f.ftsScore() != null) m.put("ftsScore", round4(f.ftsScore()));
            m.put("rrfScore", round4(f.rrfScore()));
            if (f.rerankScore() != null) m.put("rerankScore", round4(f.rerankScore()));
            if (f.vectorRank() != null) m.put("vectorRank", f.vectorRank());
            if (f.ftsRank() != null) m.put("ftsRank", f.ftsRank());
            detail.add(m);
        }
        return detail;
    }

    private String snippet(String content, int max) {
        String s = content.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    public record RetrievalResult(
            List<RetrievedChunk> vectorRecalled,
            List<RetrievedChunk> ftsRecalled,
            List<FusedChunk> fused,
            List<FusedChunk> scored,
            List<FusedChunk> reranked,
            long vectorMs,
            long ftsMs,
            long fuseMs,
            long rerankMs
    ) {}
}
