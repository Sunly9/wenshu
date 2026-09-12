package com.docmind.ingest.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Word(.docx) 解析：Heading 样式 → 标题层级；表格 → 整块 Markdown 表格文本；Word 无固定分页，pageCount 记 0 */
@Component
public class WordParser implements DocumentParser {

    @Override
    public boolean supports(String fileType) {
        return "docx".equals(fileType);
    }

    @Override
    public ParsedDocument parse(Path file) throws Exception {
        List<ParsedElement> elements = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(file))) {
            for (IBodyElementHolder holder : bodyElements(doc)) {
                if (holder.paragraph() != null) {
                    XWPFParagraph p = holder.paragraph();
                    String text = p.getText() == null ? "" : p.getText().trim();
                    if (text.isEmpty()) continue;
                    int level = headingLevel(p);
                    if (level > 0) {
                        elements.add(ParsedElement.heading(text, level, 0));
                    } else {
                        elements.add(ParsedElement.paragraph(text, 0));
                    }
                } else if (holder.table() != null) {
                    elements.add(ParsedElement.block(ParsedElement.ElementType.TABLE,
                            toMarkdownTable(holder.table()), 0));
                }
            }
        }
        return new ParsedDocument(file.getFileName().toString(), 0, elements);
    }

    private int headingLevel(XWPFParagraph p) {
        String style = p.getStyle();
        if (style == null) return 0;
        String normalized = style.toLowerCase();
        // 样式 ID 常见 "Heading1"/"heading 1"/"标题 1"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:heading|标题)\\s*(\\d)").matcher(normalized);
        return m.find() ? Math.min(Integer.parseInt(m.group(1)), 4) : 0;
    }

    private String toMarkdownTable(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText() == null ? "" : cell.getText().trim().replace("\n", " "));
            }
            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }
        return sb.toString().trim();
    }

    /** 迭代 body 元素的轻量包装（段落与表格按文档顺序） */
    private List<IBodyElementHolder> bodyElements(XWPFDocument doc) {
        List<IBodyElementHolder> list = new ArrayList<>();
        for (org.apache.poi.xwpf.usermodel.IBodyElement el : doc.getBodyElements()) {
            if (el instanceof XWPFParagraph p) list.add(new IBodyElementHolder(p, null));
            else if (el instanceof XWPFTable t) list.add(new IBodyElementHolder(null, t));
        }
        return list;
    }

    private record IBodyElementHolder(XWPFParagraph paragraph, XWPFTable table) {}
}
