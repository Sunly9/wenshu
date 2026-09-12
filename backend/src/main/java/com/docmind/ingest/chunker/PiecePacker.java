package com.docmind.ingest.chunker;

import com.docmind.index.TokenCounter;

import java.util.ArrayList;
import java.util.List;

/** 把 Piece 流打包成块：相邻打包到 target 预算，块间回抄上一块尾部整句（≤ overlap 预算） */
class PiecePacker {

    static final int TARGET_TOKENS = 320;
    static final int OVERLAP_TOKENS = 48;

    private final TokenCounter tokenCounter;

    PiecePacker(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    /** @param parentIndex 子块所属父块下标（平铺分块传 null） */
    List<ChunkDraft> pack(List<Piece> pieces, Integer parentIndex) {
        List<ChunkDraft> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentSection = null;
        Integer currentPage = null;
        int currentTokens = 0;

        for (Piece piece : pieces) {
            if (currentTokens > 0 && currentTokens + piece.tokens() > TARGET_TOKENS + OVERLAP_TOKENS) {
                emit(chunks, current, currentTokens, currentSection, currentPage, parentIndex);
                List<Piece> tail = tailOf(current.toString(), piece);
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
        emit(chunks, current, currentTokens, currentSection, currentPage, parentIndex);
        return chunks;
    }

    private void emit(List<ChunkDraft> chunks, StringBuilder sb, int tokens,
                      String section, Integer page, Integer parentIndex) {
        String content = sb.toString().trim();
        if (!content.isEmpty()) {
            chunks.add(new ChunkDraft(content, tokens, truncate(section, 512), page, parentIndex, false));
        }
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

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
