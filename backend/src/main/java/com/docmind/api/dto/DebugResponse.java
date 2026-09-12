package com.docmind.api.dto;

import java.util.List;

/**
 * 检索调试台响应：召回漏斗计数 + 候选四列得分 + 选中块 + 耗时拆解（00 号文档 §7）。
 * retrieved/meta 为检索链落库的原始 JSON 结构（camelCase 键），前端直读。
 */
public record DebugResponse(
        long queryId,
        long kbId,
        String question,
        String answer,
        long latencyMs,
        Object retrieved,      // [{chunkId,file,section,page,snippet,tokenCount,vectorScore,ftsScore,rrfScore,rerankScore,vectorRank,ftsRank}]
        List<Long> chosenIds,
        Object meta,           // {vectorCount,ftsCount,fusedCount,rerankCount,vectorMs,ftsMs,fuseMs,rerankMs}
        String createdAt
) {}
