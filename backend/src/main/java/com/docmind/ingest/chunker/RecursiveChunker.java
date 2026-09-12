package com.docmind.ingest.chunker;

import com.docmind.ingest.parser.ParsedDocument;
import com.docmind.ingest.parser.ParsedElement;
import com.docmind.index.TokenCounter;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 递归分隔符分块（工程默认值，00 号文档 §2 / §8 第 2 行）：
 * 依次按 \n\n → \n → 。 → ， 找断点拆到预算内，兜底硬切；
 * 组装时相邻块回抄上一块尾部整句（≤48 token）形成重叠。
 * 维护标题栈生成 sectionPath（如 "第3章 > 3.1"）。
 */
@Component
public class RecursiveChunker implements Chunker {

    static final int TARGET_TOKENS = 320;
    static final int OVERLAP_TOKENS = 48;
    private static final String[] SEPARATORS = {"\n\n", "\n", "。", "，"};

    private final TokenCounter tokenCounter;

    public RecursiveChunker(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    @Override
    public List<ChunkDraft> chunk(ParsedDocument doc) {
        Deque<String[]> sectionStack = new ArrayDeque<>();  // [level, title]，栈顶为最内层标题
        List<Piece> pieces = new ArrayList<>();

        for (ParsedElement el : doc.elements()) {
            if (el.type() == ParsedElement.ElementType.HEADING) {
                while (!sectionStack.isEmpty() && Integer.parseInt(sectionStack.peek()[0]) >= el.level()) {
                    sectionStack.pop();
                }
                sectionStack.push(new String[]{String.valueOf(el.level()), el.text()});
                continue;
            }
            String sectionPath = sectionPath(sectionStack);
            for (String part : splitToBudget(el.text(), TARGET_TOKENS)) {
                pieces.add(new Piece(part, tokenCounter.count(part), sectionPath, el.pageNo()));
            }
        }
        return assemble(pieces);
    }

    /** 相邻 piece 打包到 320 token；块间回抄上一块尾部整句 */
    private List<ChunkDraft> assemble(List<Piece> pieces) {
        List<ChunkDraft> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentSection = null;
        Integer currentPage = null;
        int currentTokens = 0;

        for (Piece piece : pieces) {
            if (currentTokens > 0 && currentTokens + piece.tokens() > TARGET_TOKENS + OVERLAP_TOKENS) {
                String content = current.toString().trim();
                if (!content.isEmpty()) {
                    chunks.add(new ChunkDraft(content, currentTokens,
                            truncate(currentSection, 512), currentPage));
                }
                List<Piece> tail = tailOf(content, piece);
                current = new StringBuilder();
                currentTokens = 0;
                currentSection = null;
                currentPage = null;
                for (Piece t : tail) {
                    if (currentTokens == 0) {
                        currentSection = t.section();
                        currentPage = t.page();
                    }
                    current.append(t.text());
                    currentTokens += t.tokens();
                }
            }
            if (currentTokens == 0) {
                currentSection = piece.section();
                currentPage = piece.page();
            }
            current.append(piece.text()).append('\n');
            currentTokens += piece.tokens();
        }
        String content = current.toString().trim();
        if (!content.isEmpty()) {
            chunks.add(new ChunkDraft(content, currentTokens, truncate(currentSection, 512), currentPage));
        }
        return chunks;
    }

    /** 上一块尾部整句，累计 token ≤ OVERLAP_TOKENS */
    private List<Piece> tailOf(String previousChunk, Piece nextPiece) {
        List<Piece> tail = new ArrayList<>();
        if (previousChunk == null || previousChunk.isBlank()) return tail;
        int tokens = 0;
        String[] sentences = previousChunk.split("(?<=[。！？!?])");
        for (int i = sentences.length - 1; i >= 0; i--) {
            String s = sentences[i].trim();
            if (s.isEmpty()) continue;
            int t = tokenCounter.count(s);
            if (tokens + t > OVERLAP_TOKENS) break;
            tail.add(0, new Piece(s, t, nextPiece.section(), nextPiece.page()));
            tokens += t;
        }
        return tail;
    }

    private String sectionPath(Deque<String[]> stack) {
        if (stack.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        var fromOuter = stack.descendingIterator();
        while (fromOuter.hasNext()) {
            if (!sb.isEmpty()) sb.append(" > ");
            sb.append(fromOuter.next()[1]);
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 递归按分隔符拆分；全部分隔符失效后按 token 比例硬切 */
    List<String> splitToBudget(String text, int budget) {
        return splitFrom(text, budget, 0);
    }

    /**
     * 从第 sepIndex 级分隔符开始拆。递归总是带着 sepIndex+1 进入，
     * 保证每次递归至少降一级——否则"分隔符只在末尾"这类文本会切出自身、无限递归。
     */
    private List<String> splitFrom(String text, int budget, int sepIndex) {
        if (text == null || text.isBlank()) return List.of();
        if (tokenCounter.count(text) <= budget) return List.of(text);
        if (sepIndex >= SEPARATORS.length) return hardCut(text, budget);

        String sep = SEPARATORS[sepIndex];
        if (!text.contains(sep)) {
            return splitFrom(text, budget, sepIndex + 1);
        }

        List<String> parts = new ArrayList<>();
        for (String part : text.split("(?<=" + Pattern.quote(sep) + ")", -1)) {
            if (part.isBlank()) continue;
            if (tokenCounter.count(part) > budget) {
                parts.addAll(splitFrom(part, budget, sepIndex + 1));
            } else {
                parts.add(part);
            }
        }
        if (parts.size() <= 1) {
            // 该分隔符没把文本切开（如分隔符只在末尾），降级到下一级
            return splitFrom(text, budget, sepIndex + 1);
        }
        return parts;
    }

    private List<String> hardCut(String text, int budget) {
        int total = tokenCounter.count(text);
        int chars = Math.max((int) ((double) text.length() / Math.max(total, 1) * budget), 20);
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < text.length(); start += chars) {
            String part = text.substring(start, Math.min(start + chars, text.length()));
            if (!part.isBlank()) parts.add(part);
        }
        return parts;
    }

    record Piece(String text, int tokens, String section, Integer page) {}
}
