package com.docmind.retrieve.recall;

/** 向量召回结果（子块级；父块合并 D8 接入） */
public record RetrievedChunk(
        long chunkId,
        long documentId,
        String fileName,
        String sectionPath,
        Integer pageNo,
        String content,
        int tokenCount,
        double vectorScore
) {}
