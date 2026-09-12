package com.docmind.ingest.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF 解析（D3 版本）：
 * 1) 逐行收集文本及该行最大字号（writeString 回调，按 y 坐标聚类成行）
 * 2) 正文字号取众数；明显大于正文的行判为标题，字号降序映射层级 1~4
 * 3) 页眉页脚剔除：每页首/末行跨页重复（数字归一化后出现率 ≥60%）或纯页码行
 * 4) 连续正文行合并为段落：句末标点处分段；英文行尾补空格、中文直接拼接
 * <p>
 * 表格整块保留在 D8（结构感知分块阶段）接入，本版表格按正文行处理。
 */
@Component
public class PdfParser implements DocumentParser {

    /** 字号大于正文这么多倍即视为标题 */
    static final float HEADING_RATIO = 1.15f;
    /** y 坐标相差在该值内视为同一行 */
    private static final float LINE_Y_TOLERANCE = 2.5f;

    @Override
    public boolean supports(String fileType) {
        return "pdf".equals(fileType);
    }

    @Override
    public ParsedDocument parse(Path file) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            LineCollector collector = new LineCollector();
            collector.setSortByPosition(true);
            collector.getText(pdf);
            collector.finish();
            List<ParsedElement> elements = buildElements(collector.lines(), pdf.getNumberOfPages());
            return new ParsedDocument(file.getFileName().toString(), pdf.getNumberOfPages(), elements);
        }
    }

    private List<ParsedElement> buildElements(List<Line> lines, int pageCount) {
        lines = lines.stream().filter(l -> !l.text.isBlank()).toList();
        if (lines.isEmpty()) {
            throw new IllegalStateException(
                    "未能从 PDF 中提取到任何文本——可能是扫描版/图片型 PDF（明确不支持，见 README 边界说明）");
        }

        float bodySize = bodySizeMode(lines);
        Set<Line> removed = headerFooterLines(lines, pageCount);

        List<Float> headingSizes = lines.stream()
                .filter(l -> !removed.contains(l) && isHeading(l, bodySize))
                .map(l -> l.maxFont).distinct().sorted(Comparator.reverseOrder())
                .limit(4).toList();

        List<ParsedElement> elements = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        int[] paragraphPage = {1};

        for (Line line : lines) {
            if (removed.contains(line)) continue;
            if (isTocEntry(line.text)) continue;  // 目录条目（"标题 ······ 12"）不进入正文

            if (isHeading(line, bodySize)) {
                flushParagraph(elements, paragraph, paragraphPage);
                int idx = headingSizes.indexOf(line.maxFont);
                // 超出前 4 种标题字号的（如封面/前言的花式排版）一律归入最深层级
                int level = idx < 0 ? 4 : Math.min(idx + 1, 4);
                elements.add(ParsedElement.heading(line.text, level, line.page));
                continue;
            }

            if (paragraph.isEmpty()) {
                paragraphPage[0] = line.page;
                paragraph.append(line.text);
            } else {
                paragraph.append(joinSeparator(paragraph.charAt(paragraph.length() - 1), line.text));
                paragraph.append(line.text);
            }
            if (endsWithSentenceTerminal(line.text)) {
                flushParagraph(elements, paragraph, paragraphPage);
            }
        }
        flushParagraph(elements, paragraph, paragraphPage);
        return elements;
    }

    /** 标题判定：字号显著大于正文，且不像公式/长句/目录（D8 修正：封面公式行误判为标题污染章节路径） */
    private boolean isHeading(Line line, float bodySize) {
        if (line.maxFont <= bodySize * HEADING_RATIO) return false;
        String text = line.text;
        if (text.length() > 40) return false;  // 标题不会超过 40 字
        int meaningful = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c >= 0x4E00) meaningful++;  // 字母数字或汉字
        }
        return meaningful >= text.length() * 0.5;  // 符号/乱码占多数的不是标题
    }

    /** 目录条目：以点线/省略号引导、以页码结尾 */
    private boolean isTocEntry(String text) {
        return text.matches(".*[.·…\u2026]{2,}\\s*\\d{1,4}\\s*$") && text.length() <= 80;
    }

    private float bodySizeMode(List<Line> lines) {
        Map<Integer, Integer> freq = new HashMap<>();
        int best = 0, bestCount = 0;
        for (Line l : lines) {
            int key = Math.round(l.maxFont);
            int count = freq.merge(key, 1, Integer::sum);
            if (count > bestCount) {
                bestCount = count;
                best = key;
            }
        }
        return best == 0 ? 10f : best;
    }

    private Set<Line> headerFooterLines(List<Line> lines, int pageCount) {
        Map<Integer, List<Line>> byPage = new HashMap<>();
        for (Line l : lines) {
            byPage.computeIfAbsent(l.page, k -> new ArrayList<>()).add(l);
        }

        Map<String, Integer> edgeFreq = new HashMap<>();
        for (List<Line> pageLines : byPage.values()) {
            recordEdges(pageLines, edgeFreq);
        }
        int threshold = Math.max(2, (int) Math.ceil(pageCount * 0.6));

        Set<Line> removed = new HashSet<>();
        for (List<Line> pageLines : byPage.values()) {
            Line first = pageLines.get(0);
            Line last = pageLines.get(pageLines.size() - 1);
            for (Line edge : List.of(first, last)) {
                if (edgeFreq.getOrDefault(normalize(edge.text), 0) >= threshold || isPageNumber(edge.text)) {
                    removed.add(edge);
                }
            }
        }
        return removed;
    }

    private void recordEdges(List<Line> pageLines, Map<String, Integer> edgeFreq) {
        Line first = pageLines.get(0);
        Line last = pageLines.get(pageLines.size() - 1);
        edgeFreq.merge(normalize(first.text), 1, Integer::sum);
        edgeFreq.merge(normalize(last.text), 1, Integer::sum);
    }

    private String normalize(String s) {
        return s.replaceAll("\\d+", "#").replaceAll("\\s+", "").trim();
    }

    private boolean isPageNumber(String s) {
        String t = s.trim();
        return !t.isEmpty() && t.length() <= 8 && t.matches("[0-9ivxIVX\\-–—/ .]+");
    }

    private boolean endsWithSentenceTerminal(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(s.length() - 1);
        return "。！？；：!?;:".indexOf(c) >= 0 || c == '.';
    }

    /** 英文断行补空格，中文直接拼接 */
    private String joinSeparator(char lastChar, String next) {
        boolean lastIsAsciiWord = lastChar < 128 && Character.isLetterOrDigit(lastChar);
        boolean nextIsAsciiWord = !next.isEmpty() && next.charAt(0) < 128 && Character.isLetterOrDigit(next.charAt(0));
        return (lastIsAsciiWord && nextIsAsciiWord) ? " " : "";
    }

    private void flushParagraph(List<ParsedElement> elements, StringBuilder sb, int[] pageHolder) {
        if (!sb.isEmpty()) {
            elements.add(ParsedElement.paragraph(sb.toString(), pageHolder[0]));
            sb.setLength(0);
        }
    }

    // ---------------- 行收集 ----------------

    static final class Line {
        final int page;
        final StringBuilder content = new StringBuilder();
        float maxFont = 0f;
        String text;

        Line(int page) {
            this.page = page;
        }

        void append(String s, float font) {
            content.append(s);
            maxFont = Math.max(maxFont, font);
            text = content.toString().trim();
        }
    }

    /** 把 PDFTextStripper 的 writeString 回调按 (页, y) 聚合成行，记录行内最大字号 */
    static final class LineCollector extends PDFTextStripper {

        private final List<Line> lines = new ArrayList<>();
        private Line current;
        private int currentPage = -1;
        private float lastY = Float.MIN_VALUE;

        LineCollector() throws IOException {}

        List<Line> lines() {
            return lines;
        }

        void finish() {
            if (current != null) {
                lines.add(current);
                current = null;
            }
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text == null || text.isBlank() || positions.isEmpty()) return;
            int page = getCurrentPageNo();
            float y = positions.get(0).getYDirAdj();
            float maxFont = 0f;
            for (TextPosition p : positions) {
                if (!p.getUnicode().isBlank()) {
                    maxFont = Math.max(maxFont, p.getFontSizeInPt());
                }
            }
            if (current != null && page == currentPage && Math.abs(y - lastY) <= LINE_Y_TOLERANCE) {
                current.append(text, maxFont);
            } else {
                finish();
                current = new Line(page);
                current.append(text, maxFont);
                currentPage = page;
                lastY = y;
            }
        }
    }
}
