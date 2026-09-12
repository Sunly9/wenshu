package com.docmind.ingest.chunker;

import com.docmind.ingest.parser.ParsedDocument;
import com.docmind.ingest.parser.ParsedElement;
import com.docmind.index.TokenCounter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureAwareChunkerTest {

    static TokenCounter tokenCounter;

    @BeforeAll
    static void setup() {
        Path dir = Path.of("models/bge-small-zh-v1.5");
        Assumptions.assumeTrue(Files.exists(dir.resolve("tokenizer.json")), "本机无模型文件，跳过");
        tokenCounter = new TokenCounter("models/bge-small-zh-v1.5");
    }

    @Test
    void 父子分块_表格整块保留_章节路径正确() {
        String bigTable = """
                | 算法 | 平均时间 | 最坏时间 | 空间 | 稳定性 |
                |---|---|---|---|---|
                | 冒泡排序 | O(n²) | O(n²) | O(1) | 稳定 |
                | 快速排序 | O(nlogn) | O(n²) | O(logn) | 不稳定 |
                | 归并排序 | O(nlogn) | O(nlogn) | O(n) | 稳定 |
                | 堆排序 | O(nlogn) | O(nlogn) | O(1) | 不稳定 |
                """;
        ParsedDocument doc = new ParsedDocument("t.pdf", 5, List.of(
                ParsedElement.heading("第9章 排序", 1, 1),
                ParsedElement.paragraph("排序是将一组数据按关键字递增或递减排列的操作。".repeat(30), 1),
                ParsedElement.block(ParsedElement.ElementType.TABLE, bigTable, 3),
                ParsedElement.heading("9.3 交换排序", 2, 4),
                ParsedElement.paragraph("冒泡排序的基本思想是相邻元素两两比较。".repeat(40), 4)
        ));

        ChunkResult result = new StructureAwareChunker(tokenCounter).chunk(doc);

        // 表格：在父块和子块中都应作为一整块出现，绝不切断
        List<ChunkDraft> tableChildren = result.children().stream()
                .filter(ChunkDraft::table).toList();
        assertEquals(1, tableChildren.size(), "表格应是唯一的一个整块子块");
        assertTrue(tableChildren.get(0).content().contains("冒泡排序"));
        assertTrue(tableChildren.get(0).content().contains("堆排序"));
        assertTrue(tableChildren.get(0).content().contains("稳定性"));
        long tableParents = result.parents().stream()
                .filter(p -> p.content().contains("| 算法 |")).count();
        assertEquals(1, tableParents, "表格应独占一个父块");

        // 常规内容：子块 ≤ 320+48 预算，且有父块挂靠
        for (ChunkDraft child : result.children()) {
            if (child.table()) continue;
            assertTrue(child.tokenCount() <= 320 + 48 + 30, "子块超预算：" + child.tokenCount());
            assertTrue(child.parentIndex() != null && child.parentIndex() < result.parents().size(),
                    "子块必须挂父块");
        }

        // 父块 ≤ 1024+预算内
        for (ChunkDraft parent : result.parents()) {
            assertTrue(parent.tokenCount() <= 1024 + 200, "父块超预算：" + parent.tokenCount());
            assertNull(parent.parentIndex());
        }

        // 章节路径：第9章下的段落与 9.3 下的段落路径不同
        assertTrue(result.children().stream().anyMatch(c -> "第9章 排序".equals(c.sectionPath())));
        assertTrue(result.children().stream().anyMatch(c -> "第9章 排序 > 9.3 交换排序".equals(c.sectionPath())));

        // 父块内容应包含其子块内容（小块检索、大块返回）
        ChunkDraft someChild = result.children().stream()
                .filter(c -> c.sectionPath() != null && c.sectionPath().contains("9.3")).findFirst().orElseThrow();
        assertTrue(result.parents().get(someChild.parentIndex()).content().contains("冒泡排序的基本思想"));
    }

    private static void assertNull(Object o) {
        assertTrue(o == null, "应为 null");
    }
}
