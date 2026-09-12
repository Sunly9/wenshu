package com.docmind.retrieve.fusion;

import com.docmind.retrieve.recall.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** RRF 融合：score(d) = Σ 1/(k + rank)，k=60（00 号文档 §6 冻结参数） */
@Component
public class RrfFusion {

    public static final int K = 60;

    public List<FusedChunk> fuse(List<RetrievedChunk> byVector, List<RetrievedChunk> byFts, int limit) {
        Map<Long, Builder> builders = new HashMap<>();
        for (int r = 0; r < byVector.size(); r++) {
            RetrievedChunk c = byVector.get(r);
            builders.computeIfAbsent(c.chunkId(), id -> new Builder(c))
                    .vectorScore(c.vectorScore()).vectorRank(r + 1)
                    .addRrf(r);
        }
        for (int r = 0; r < byFts.size(); r++) {
            RetrievedChunk c = byFts.get(r);
            builders.computeIfAbsent(c.chunkId(), id -> new Builder(c))
                    .ftsScore(c.vectorScore()).ftsRank(r + 1)
                    .addRrf(r);
        }
        List<FusedChunk> fused = new ArrayList<>();
        for (Builder b : builders.values()) {
            fused.add(new FusedChunk(b.chunk, b.vectorScore, b.ftsScore, b.rrf, b.vectorRank, b.ftsRank));
        }
        fused.sort((a, b2) -> Double.compare(b2.rrfScore(), a.rrfScore()));
        return fused.size() > limit ? fused.subList(0, limit) : fused;
    }

    private static final class Builder {
        final RetrievedChunk chunk;
        Double vectorScore;
        Double ftsScore;
        double rrf;
        Integer vectorRank;
        Integer ftsRank;

        Builder(RetrievedChunk chunk) {
            this.chunk = chunk;
        }

        Builder vectorScore(double score) {
            this.vectorScore = score;
            return this;
        }

        Builder ftsScore(double score) {
            this.ftsScore = score;
            return this;
        }

        Builder vectorRank(int rank) {
            this.vectorRank = rank;
            return this;
        }

        Builder ftsRank(int rank) {
            this.ftsRank = rank;
            return this;
        }

        Builder addRrf(int zeroBasedRank) {
            this.rrf += 1.0 / (K + zeroBasedRank + 1);
            return this;
        }
    }
}
