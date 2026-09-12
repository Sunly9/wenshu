package com.docmind.ingest.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfParserTest {

    @TempDir
    Path tmp;

    /** 造一个两页 PDF：两页相同页眉 + 页码页脚 + 大字标题 + 两行正文 */
    private Path buildSamplePdf() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDType1Font body = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            for (int p = 1; p <= 2; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                pdf.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                    writeLine(cs, body, 9, 300, 820, "Wenshu Report");           // 页眉
                    if (p == 1) {
                        writeLine(cs, bold, 16, 72, 760, "Chapter One");          // 标题
                    }
                    writeLine(cs, body, 11, 72, p == 1 ? 700 : 760, "This is the first line of body text");
                    writeLine(cs, body, 11, 72, p == 1 ? 685 : 745, "and this is the second line.");
                    writeLine(cs, body, 9, 300, 40, String.valueOf(p));           // 页码
                }
            }
            Path file = tmp.resolve("sample.pdf");
            pdf.save(file.toFile());
            return file;
        }
    }

    private void writeLine(PDPageContentStream cs, PDType1Font font, float size,
                           float x, float y, String text) throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    @Test
    void 标题识别_页眉页脚剔除_段落合并() throws Exception {
        ParsedDocument doc = new PdfParser().parse(buildSamplePdf());
        List<ParsedElement> es = doc.elements();
        assertEquals(2, doc.pageCount());

        // 全部文本里不应再出现页眉与页码
        String all = es.stream().map(ParsedElement::text).reduce("", (a, b) -> a + b);
        assertFalse(all.contains("Wenshu Report"), "页眉应被剔除");
        assertTrue(es.stream().noneMatch(e -> e.text().matches("[12]")), "页码应被剔除");

        // 标题：16pt vs 正文 11pt → level 1
        ParsedElement heading = es.get(0);
        assertEquals(ParsedElement.ElementType.HEADING, heading.type());
        assertEquals("Chapter One", heading.text());
        assertEquals(1, heading.level());

        // 两页正文各合并成段落，英文断行补空格
        assertEquals(3, es.size());  // 1 标题 + 2 页 × 1 段落
        assertTrue(es.get(1).text().startsWith("This is the first line of body text and this is the second line."));
        assertTrue(es.get(2).text().startsWith("This is the first line of body text"));
        assertEquals(2, es.get(2).pageNo());
    }

    @Test
    void 空文本PDF_抛出明确错误() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage(PDRectangle.A4));
            Path file = tmp.resolve("blank.pdf");
            pdf.save(file.toFile());
            Exception ex = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> new PdfParser().parse(file));
            assertTrue(ex.getMessage().contains("扫描版"), "错误信息应说明可能是扫描版：" + ex.getMessage());
        }
    }
}
