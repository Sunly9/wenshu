package com.docmind.ingest.chunker;

import com.docmind.index.TokenCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 按 \n\n → \n → 。 → ， 递归找断点拆到预算内，兜底按 token 比例硬切（D4 排障：递归强制降级保证进展） */
class TokenSplitter {

    private static final String[] SEPARATORS = {"\n\n", "\n", "。", "，"};

    private final TokenCounter tokenCounter;

    TokenSplitter(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    public List<String> split(String text, int budget) {
        return splitFrom(text, budget, 0);
    }

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
}
