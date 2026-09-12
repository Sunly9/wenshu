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

    public RetrievalService(VectorRecall vectorRecall, FtsRecall ftsRecall, RrfFusion rrfFusion,
                            com.docmind.retrieve.rerank.RerankClient rerankClient) {
        this.vectorRecall = vectorRecall;
        this.ftsRecall = ftsRecall;
        this.rrfFusion = rrfFusion;
        this.rerankClient = rerankClient;
    }

    public RetrievalResult recall(long kbId, String question) {
        long t0 = System.currentTimeMillis();
        List<RetrievedChunk> byVector = vectorRecall.recall(kbId, question, RECALL_TOP_K);
        long vectorMs = System.currentTimeMillis() - t0;

        long t1 = System.currentTimeMillis();
        List<RetrievedChunk> byFts = ftsRecall.recall(kbId, question, RECALL_TOP_K);
        long ftsMs = System.currentTimeMillis() - t1;

        long t2 = System.currentTimeMillis();
        List<FusedChunk> fused = rrfFusion.fuse(byVector, byFts, FUSED_LIMIT);
        long fuseMs = System.currentTimeMillis() - t2;

        long t3 = System.currentTimeMillis();
        List<FusedChunk> rerankInput = fused.size() > RERANK_INPUT
                ? new ArrayList<>(fused.subList(0, RERANK_INPUT)) : fused;
        List<FusedChunk> scored = rerank(kbId, question, rerankInput);
        long rerankMs = System.currentTimeMillis() - t3;
        List<FusedChunk> reranked = scored.size() > RERANK_KEEP
                ? new ArrayList<>(scored.subList(0, RERANK_KEEP)) : scored;

        log.info("检索完成 kb={} 向量{}条/{}ms 关键词{}条/{}ms 融合{}条/{}ms 精排留{}条/{}ms 最高分{}",
                kbId, byVector.size(), vectorMs, byFts.size(), ftsMs, fused.size(), fuseMs,
                reranked.size(), rerankMs,
                reranked.isEmpty() ? "-" : String.format("%.3f", reranked.get(0).rerankScore()));
        return new RetrievalResult(byVector, byFts, scored, reranked, vectorMs, ftsMs, fuseMs, rerankMs);
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

    public record RetrievalResult(
            List<RetrievedChunk> vectorRecalled,
            List<RetrievedChunk> ftsRecalled,
            List<FusedChunk> fused,
            List<FusedChunk> reranked,
            long vectorMs,
            long ftsMs,
            long fuseMs,
            long rerankMs
    ) {}
}
