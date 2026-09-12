package com.docmind.ingest.parser;

import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Markdown 解析：标题层级/段落/列表/表格/代码块（无分页概念，pageNo 恒为 1） */
@Component
public class MarkdownParser implements DocumentParser {

    private final Parser parser;

    public MarkdownParser() {
        MutableDataSet options = new MutableDataSet();
        this.parser = Parser.builder(options)
                .extensions(List.of(com.vladsch.flexmark.ext.tables.TablesExtension.create()))
                .build();
    }

    @Override
    public boolean supports(String fileType) {
        return "md".equals(fileType);
    }

    @Override
    public ParsedDocument parse(Path file) throws Exception {
        Node root = parser.parse(Files.readString(file));
        List<ParsedElement> elements = new java.util.ArrayList<>();
        collect(root, elements);
        return new ParsedDocument(file.getFileName().toString(), 1, elements);
    }

    private void collect(Node root, List<ParsedElement> elements) {
        for (Node node : root.getChildren()) {
            if (node instanceof Heading heading) {
                elements.add(ParsedElement.heading(
                        new TextCollectingVisitor().collectAndGetText(heading).trim(),
                        heading.getLevel(), 1));
            } else if (node instanceof BulletList || node instanceof OrderedList) {
                elements.add(ParsedElement.block(ParsedElement.ElementType.LIST, node.getChars().toString().trim(), 1));
            } else if (node instanceof TableBlock) {
                // 表格保留 Markdown 原文（管道分隔），D8 结构感知分块按整块处理
                elements.add(ParsedElement.block(ParsedElement.ElementType.TABLE, node.getChars().toString().trim(), 1));
            } else if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
                elements.add(ParsedElement.block(ParsedElement.ElementType.CODE, node.getChars().toString().trim(), 1));
            } else if (node instanceof Paragraph paragraph) {
                elements.add(ParsedElement.paragraph(paragraph.getChars().toString().trim(), 1));
            } else {
                // BlockQuote / HtmlBlock 等：递归展开为普通元素
                collect(node, elements);
            }
        }
    }
}
