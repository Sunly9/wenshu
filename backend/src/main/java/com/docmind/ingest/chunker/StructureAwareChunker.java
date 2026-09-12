package com.docmind.ingest.chunker;

import com.docmind.ingest.parser.ParsedDocument;
import com.docmind.ingest.parser.ParsedElement;
import com.docmind.index.TokenCounter;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 结构感知分块（主策略，00 号文档 §2 / 03 号文档 §4.2）：
 * 1) 标题栈维护章节路径；2) 表格整块保留、独占父子关系、绝不切断；
 * 3) 父子分块：连续元素攒成 ≤1024 token 的父块（返回给模型），
 *    父块内文本按 320 token 切成子块（参与检索），子块携带 parentIndex。
 */
@Component
public class StructureAwareChunker implements Chunker {

    static final int PARENT_TOKENS = 1024;
    private static final int HEADING_MAX_CHARS = 40;  // 超长"标题"多半是公式/目录行误判

    private final TokenCounter tokenCounter;
    private final TokenSplitter splitter;
    private final PiecePacker packer;

    public StructureAwareChunker(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
        this.splitter = new TokenSplitter(tokenCounter);
        this.packer = new PiecePacker(tokenCounter);
    }

    @Override
    public ChunkResult chunk(ParsedDocument doc) {
        Deque<String[]> sectionStack = new ArrayDeque<>();
        List<ChunkDraft> parents = new ArrayList<>();
        List<ChunkDraft> children = new ArrayList<>();
        List<ParsedElement> buffer = new ArrayList<>();  // 正在攒的父块内容

        for (ParsedElement el : doc.elements()) {
            if (el.type() == ParsedElement.ElementType.HEADING) {
                flushParent(buffer, sectionStack, parents, children);
                if (el.text().length() <= HEADING_MAX_CHARS) {
                    while (!sectionStack.isEmpty() && Integer.parseInt(sectionStack.peek()[0]) >= el.level()) {
                        sectionStack.pop();
                    }
                    sectionStack.push(new String[]{String.valueOf(el.level()), el.text()});
                }
                continue;
            }
            if (el.type() == ParsedElement.ElementType.TABLE || el.type() == ParsedElement.ElementType.CODE) {
                // 表格/代码整块保留：独占一对父子，超预算也不切（切断即失去语义）
                flushParent(buffer, sectionStack, parents, children);
                String section = sectionPath(sectionStack);
                int tokens = tokenCounter.count(el.text());
                parents.add(new ChunkDraft(el.text(), tokens, section, el.pageNo(), null,
                        el.type() == ParsedElement.ElementType.TABLE));
                children.add(new ChunkDraft(el.text(), tokens, section, el.pageNo(), parents.size() - 1,
                        el.type() == ParsedElement.ElementType.TABLE));
                continue;
            }
            buffer.add(el);
            if (bufferTokens(buffer) > PARENT_TOKENS) {
                flushParent(buffer, sectionStack, parents, children);
            }
        }
        flushParent(buffer, sectionStack, parents, children);
        return new ChunkResult(parents, children);
    }

    /** 把攒的元素落成一个父块 + 若干子块；调用点的标题栈即这些元素的章节路径 */
    private void flushParent(List<ParsedElement> buffer, Deque<String[]> sectionStack,
                             List<ChunkDraft> parents, List<ChunkDraft> children) {
        if (buffer.isEmpty()) return;
        String parentContent = joinContent(buffer);
        String section = truncate(sectionPath(sectionStack), 512);
        Integer page = buffer.get(0).pageNo();

        parents.add(new ChunkDraft(parentContent, tokenCounter.count(parentContent), section, page, null, false));
        int parentIndex = parents.size() - 1;

        List<Piece> pieces = new ArrayList<>();
        for (ParsedElement el : buffer) {
            for (String part : splitter.split(el.text(), PiecePacker.TARGET_TOKENS)) {
                pieces.add(new Piece(part, tokenCounter.count(part), section, el.pageNo()));
            }
        }
        children.addAll(packer.pack(pieces, parentIndex));
        buffer.clear();
    }

    private int bufferTokens(List<ParsedElement> buffer) {
        int tokens = 0;
        for (ParsedElement el : buffer) {
            tokens += tokenCounter.count(el.text());
        }
        return tokens;
    }

    private String joinContent(List<ParsedElement> buffer) {
        StringBuilder sb = new StringBuilder();
        for (ParsedElement el : buffer) {
            sb.append(el.text()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
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
