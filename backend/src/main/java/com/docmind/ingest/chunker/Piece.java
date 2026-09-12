package com.docmind.ingest.chunker;

/** 组装过程中的最小单元：已被计数的文本片段 */
public record Piece(String text, int tokens, String section, Integer page) {}
