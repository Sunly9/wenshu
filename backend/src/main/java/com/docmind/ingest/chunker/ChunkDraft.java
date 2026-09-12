package com.docmind.ingest.chunker;

/** 分片产物（03 号文档 §3）；chunk_index 由入库时统一编号 */
public record ChunkDraft(String content, int tokenCount, String sectionPath, Integer pageNo) {}
