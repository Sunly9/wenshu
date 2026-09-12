package com.docmind.ingest.parser;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实语料冒烟：仅在本机存在语料时执行（CI 上自动跳过），打印前若干元素供人工检查解析质量 */
class PdfRealCorpusTest {

    private static final Path TEXTBOOK =
            Path.of("D:/复习资料/数据结构与算法/01_教材/数据结构(C语言版)_严蔚敏_清华大学出版社.pdf");

    @Test
    void 严蔚敏教材解析冒烟() throws Exception {
        Assumptions.assumeTrue(Files.exists(TEXTBOOK), "本机无该语料，跳过");

        ParsedDocument doc = new PdfParser().parse(TEXTBOOK);

        assertTrue(doc.pageCount() > 100, "教材应有几百页，实际 " + doc.pageCount());
        assertTrue(doc.elements().size() > 500, "元素过少，疑似解析异常");
        assertTrue(doc.countByType(ParsedElement.ElementType.HEADING) > 10, "标题过少，层级识别疑似失效");

        System.out.println("=== 解析概览 ===");
        System.out.println("页数: " + doc.pageCount() + ", 元素: " + doc.elements().size()
                + ", 标题: " + doc.countByType(ParsedElement.ElementType.HEADING)
                + ", 段落: " + doc.countByType(ParsedElement.ElementType.PARAGRAPH));
        System.out.println("=== 前 20 个元素 ===");
        doc.elements().stream().limit(20).forEach(e -> {
            String head = e.text().length() > 50 ? e.text().substring(0, 50) + "…" : e.text();
            System.out.printf("%-9s L%d P%d | %s%n", e.type(), e.level(), e.pageNo(), head);
        });
    }
}
