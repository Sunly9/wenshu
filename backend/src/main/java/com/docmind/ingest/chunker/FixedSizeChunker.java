package com.docmind.ingest.chunker;

import com.docmind.ingest.parser.ParsedDocument;
import com.docmind.ingest.parser.ParsedElement;
import com.docmind.index.TokenCounter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定长度分块（消融实验基线，00 号文档 §8 第 1 行）：
 * 按 token 预算换算字符宽度硬切（允许切断句子/表格——这正是基线要暴露的问题），
 * 15% 重叠防止边界语义完全丢失。
 */
@Component
public class FixedSizeChunker implements Chunker {

    static final int TARGET_TOKENS = 320;
    static final double OVERLAP_RATIO = 0.15;

    private final TokenCounter tokenCounter;

    public FixedSizeChunker(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    @Override
    public List<ChunkDraft> chunk(ParsedDocument doc) {
        StringBuilder all = new StringBuilder();
        List<int[]> pageSpans = new ArrayList<>();  // [start, end, pageNo]
        for (ParsedElement el : doc.elements()) {
            int start = all.length();
            all.append(el.text()).append('\n');
            pageSpans.add(new int[]{start, all.length(), el.pageNo()});
        }
        String text = all.toString();
        if (text.isBlank()) return List.of();

        int totalTokens = tokenCounter.count(text);
        double charsPerToken = totalTokens == 0 ? 1 : (double) text.length() / totalTokens;
        int targetChars = (int) (TARGET_TOKENS * charsPerToken);
        int stepChars = (int) (targetChars * (1 - OVERLAP_RATIO));
        if (targetChars < 50) targetChars = 50;

        List<ChunkDraft> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += Math.max(stepChars, 1)) {
            int end = Math.min(start + targetChars, text.length());
            String content = text.substring(start, end).trim();
            if (content.isEmpty()) continue;
            chunks.add(new ChunkDraft(content, tokenCounter.count(content), null, pageAt(pageSpans, start)));
            if (end >= text.length()) break;
        }
        return chunks;
    }

    private Integer pageAt(List<int[]> pageSpans, int charOffset) {
        for (int[] span : pageSpans) {
            if (charOffset < span[1]) return span[2];
        }
        return pageSpans.isEmpty() ? null : pageSpans.get(pageSpans.size() - 1)[2];
    }
}
