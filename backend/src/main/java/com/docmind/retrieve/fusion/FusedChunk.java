package com.docmind.retrieve.fusion;

import com.docmind.retrieve.recall.RetrievedChunk;

/** RRF 融合后的候选：两路得分与排名 + 融合分 + rerankScore（精排后填充） */
public record FusedChunk(
        RetrievedChunk chunk,
        Double vectorScore,
        Double ftsScore,
        double rrfScore,
        Integer vectorRank,
        Integer ftsRank,
        Double rerankScore
) {
    public FusedChunk(RetrievedChunk chunk, Double vectorScore, Double ftsScore, double rrfScore,
                      Integer vectorRank, Integer ftsRank) {
        this(chunk, vectorScore, ftsScore, rrfScore, vectorRank, ftsRank, null);
    }

    public FusedChunk withRerank(double rerankScore) {
        return new FusedChunk(chunk, vectorScore, ftsScore, rrfScore, vectorRank, ftsRank, rerankScore);
    }
}
