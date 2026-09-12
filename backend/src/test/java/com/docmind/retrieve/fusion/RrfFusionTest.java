package com.docmind.retrieve.fusion;

import com.docmind.retrieve.recall.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFusionTest {

    private RetrievedChunk chunk(long id) {
        return new RetrievedChunk(id, 1, null, "f.pdf", "s", 1, "c", 100, 0.9);
    }

    @Test
    void 两路都命中的块排名靠前_得分正确() {
        List<RetrievedChunk> byVector = List.of(chunk(1), chunk(2), chunk(3));
        List<RetrievedChunk> byFts = List.of(chunk(2), chunk(4));

        List<FusedChunk> fused = new RrfFusion().fuse(byVector, byFts, 30);

        // 块2 两路命中：1/(60+2) + 1/(60+1)；块1 仅向量第1：1/61 —— 块2 应排第一
        assertEquals(2, fused.get(0).chunk().chunkId());
        assertEquals(1.0 / 62 + 1.0 / 61, fused.get(0).rrfScore(), 1e-9);
        assertEquals(4, fused.size());
        assertTrue(fused.get(0).vectorRank() == 2 && fused.get(0).ftsRank() == 1);

        // limit 生效
        assertEquals(2, new RrfFusion().fuse(byVector, byFts, 2).size());
    }
}
