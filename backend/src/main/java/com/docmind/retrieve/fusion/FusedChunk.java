package com.docmind.retrieve.fusion;

import com.docmind.retrieve.recall.RetrievedChunk;

/** RRF 融合后的候选：两路得分与排名 + 融合分（rerankScore D11 追加） */
public record FusedChunk(
        RetrievedChunk chunk,
        Double vectorScore,
        Double ftsScore,
        double rrfScore,
        Integer vectorRank,
        Integer ftsRank
) {}
