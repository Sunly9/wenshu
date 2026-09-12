package com.docmind.ingest.chunker;

/** 分片产物（03 号文档 §3）；parentIndex 指向本次分块结果中的父块下标（无父块为 null） */
public record ChunkDraft(String content, int tokenCount, String sectionPath, Integer pageNo,
                         Integer parentIndex, boolean table) {

    public ChunkDraft(String content, int tokenCount, String sectionPath, Integer pageNo) {
        this(content, tokenCount, sectionPath, pageNo, null, false);
    }
}
