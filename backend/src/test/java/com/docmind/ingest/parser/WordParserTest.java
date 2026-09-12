package com.docmind.ingest.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WordParserTest {

    @TempDir
    Path tmp;

    @Test
    void 标题段落表格_全部识别() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph h1 = doc.createParagraph();
            h1.setStyle("Heading1");
            h1.createRun().setText("第1章 绪论");

            XWPFParagraph body = doc.createParagraph();
            body.createRun().setText("数据结构是研究数据组织与存储的学科。");

            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("算法");
            table.getRow(0).getCell(1).setText("复杂度");
            table.getRow(1).getCell(0).setText("快排");
            table.getRow(1).getCell(1).setText("O(nlogn)");

            Path file = tmp.resolve("test.docx");
            try (var out = Files.newOutputStream(file)) {
                doc.write(out);
            }

            ParsedDocument parsed = new WordParser().parse(file);
            List<ParsedElement> es = parsed.elements();

            assertEquals(3, es.size());
            assertEquals(ParsedElement.ElementType.HEADING, es.get(0).type());
            assertEquals("第1章 绪论", es.get(0).text());
            assertEquals(1, es.get(0).level());
            assertEquals(ParsedElement.ElementType.PARAGRAPH, es.get(1).type());
            assertEquals(ParsedElement.ElementType.TABLE, es.get(2).type());
            assertEquals("| 算法 | 复杂度 |\n| 快排 | O(nlogn) |", es.get(2).text());
        }
    }
}
