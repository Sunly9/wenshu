package com.docmind.retrieve.recall;

/** 召回结果（子块级，携带 parent_id 供父块合并） */
public record RetrievedChunk(
        long chunkId,
        long documentId,
        Long parentChunkId,
        String fileName,
        String sectionPath,
        Integer pageNo,
        String content,
        int tokenCount,
        double vectorScore
) {}
