package com.docmind.ingest.parser;

/**
 * 解析产物：文档 = 有序元素流（03 号文档 §3）。
 * 所有 Parser 输出该结构，所有 Chunker 消费该结构。
 */
public record ParsedElement(ElementType type, String text, int level, int pageNo) {

    public enum ElementType { HEADING, PARAGRAPH, TABLE, LIST, CODE }

    public static ParsedElement heading(String text, int level, int pageNo) {
        return new ParsedElement(ElementType.HEADING, text, level, pageNo);
    }

    public static ParsedElement paragraph(String text, int pageNo) {
        return new ParsedElement(ElementType.PARAGRAPH, text, 0, pageNo);
    }

    public static ParsedElement block(ElementType type, String text, int pageNo) {
        return new ParsedElement(type, text, 0, pageNo);
    }
}
