package com.docmind.retrieve;

import com.docmind.retrieve.fusion.FusedChunk;
import com.docmind.retrieve.fusion.RrfFusion;
import com.docmind.retrieve.recall.FtsRecall;
import com.docmind.retrieve.recall.RetrievedChunk;
import com.docmind.retrieve.recall.VectorRecall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索编排：向量 50 + 关键词 50 → RRF 融合 30（00 号文档 §6 冻结参数）。
 * 每阶段耗时与得分全部保留在结果里——检索调试台的数据源（Rerank 在 D11 插入融合之后）。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    static final int RECALL_TOP_K = 50;
    static final int FUSED_LIMIT = 30;

    private final VectorRecall vectorRecall;
    private final FtsRecall ftsRecall;
    private final RrfFusion rrfFusion;

    public RetrievalService(VectorRecall vectorRecall, FtsRecall ftsRecall, RrfFusion rrfFusion) {
        this.vectorRecall = vectorRecall;
        this.ftsRecall = ftsRecall;
        this.rrfFusion = rrfFusion;
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

        log.info("检索完成 kb={} 向量{}条/{}ms 关键词{}条/{}ms 融合{}条/{}ms",
                kbId, byVector.size(), vectorMs, byFts.size(), ftsMs, fused.size(), fuseMs);
        return new RetrievalResult(byVector, byFts, fused, vectorMs, ftsMs, fuseMs);
    }

    public record RetrievalResult(
            List<RetrievedChunk> vectorRecalled,
            List<RetrievedChunk> ftsRecalled,
            List<FusedChunk> fused,
            long vectorMs,
            long ftsMs,
            long fuseMs
    ) {}
}
