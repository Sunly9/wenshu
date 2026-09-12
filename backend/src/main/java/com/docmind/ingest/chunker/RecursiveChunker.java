package com.docmind.ingest.chunker;

import com.docmind.ingest.parser.ParsedDocument;
import com.docmind.ingest.parser.ParsedElement;
import com.docmind.index.TokenCounter;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 递归分隔符分块（工程默认值，00 号文档 §2 / §8 第 2 行），实现委托给 TokenSplitter + PiecePacker */
@Component
public class RecursiveChunker implements Chunker {

    private final TokenCounter tokenCounter;
    private final TokenSplitter splitter;
    private final PiecePacker packer;

    public RecursiveChunker(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
        this.splitter = new TokenSplitter(tokenCounter);
        this.packer = new PiecePacker(tokenCounter);
    }

    @Override
    public ChunkResult chunk(ParsedDocument doc) {
        Deque<String[]> sectionStack = new ArrayDeque<>();  // [level, title]
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
            for (String part : splitter.split(el.text(), PiecePacker.TARGET_TOKENS)) {
                pieces.add(new Piece(part, tokenCounter.count(part), sectionPath, el.pageNo()));
            }
        }
        return ChunkResult.flat(packer.pack(pieces, null));
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
}
