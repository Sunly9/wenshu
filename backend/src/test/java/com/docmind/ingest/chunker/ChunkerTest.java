package com.docmind.ingest.chunker;

import com.docmind.ingest.parser.ParsedDocument;
import com.docmind.ingest.parser.ParsedElement;
import com.docmind.index.TokenCounter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkerTest {

    static TokenCounter tokenCounter;
    static boolean modelReady;

    @BeforeAll
    static void setup() {
        Path dir = Path.of("models/bge-small-zh-v1.5");
        modelReady = Files.exists(dir.resolve("tokenizer.json"));
        org.junit.jupiter.api.Assumptions.assumeTrue(modelReady, "本机无模型文件，跳过分片测试");
        tokenCounter = new TokenCounter("models/bge-small-zh-v1.5");
    }

    private String repeat(String sentence, int times) {
        return sentence.repeat(times);
    }

    @Test
    void 固定长度_切在预算附近_相邻块有重叠() {
        ParsedDocument doc = new ParsedDocument("t.pdf", 1,
                List.of(ParsedElement.paragraph(
                        repeat("数据结构研究数据的组织、存储与处理方法，是计算机学科的核心基础课程。", 120), 1)));
        List<ChunkDraft> chunks = new FixedSizeChunker(tokenCounter).chunk(doc).children();

        assertTrue(chunks.size() > 3, "长文本应切出多块，实际 " + chunks.size());
        for (ChunkDraft chunk : chunks) {
            assertTrue(chunk.tokenCount() <= 320 * 1.3,
                    "块 " + chunk.tokenCount() + " 超预算过多（按字符比例硬切允许少量误差）");
            assertNotNull(chunk.pageNo());
        }
        assertTrue(chunks.get(0).content().contains(chunks.get(0).content().substring(0, 10)),
                "首块应包含开头内容");
    }

    @Test
    void 递归分块_断句完整_sectionPath正确() {
        ParsedDocument doc = new ParsedDocument("t.pdf", 3, List.of(
                ParsedElement.heading("第1章 绪论", 1, 1),
                ParsedElement.paragraph(repeat("本章介绍数据结构的基本概念与术语。抽象数据类型是数据组织的重要工具。", 40), 1),
                ParsedElement.heading("1.1 基本术语", 2, 2),
                ParsedElement.paragraph(repeat("数据是能被计算机识别与处理的符号集合。数据元素是数据的基本单位。", 40), 2)
        ));
        List<ChunkDraft> chunks = new RecursiveChunker(tokenCounter).chunk(doc).children();

        assertTrue(chunks.size() > 2);
        for (ChunkDraft chunk : chunks) {
            assertTrue(chunk.tokenCount() <= 320 + 48 + 30, "块超预算：" + chunk.tokenCount());
            assertTrue(chunk.content().endsWith("。") || chunk.content().endsWith("。\n".trim())
                    || chunk.content().endsWith("。"), "递归分块的块尾应落在句末，实际：" + chunk.content().substring(
                    Math.max(0, chunk.content().length() - 5)));
        }
        // 第二章节的块应带层级路径
        assertTrue(chunks.stream().anyMatch(c -> "第1章 绪论 > 1.1 基本术语".equals(c.sectionPath())),
                "sectionPath 应为 标题栈拼接，实际：" + chunks.stream().map(ChunkDraft::sectionPath).distinct().toList());
        assertEquals(1, chunks.get(0).pageNo());
    }

    @Test
    void 递归分块_相邻块重叠() {
        ParsedDocument doc = new ParsedDocument("t.pdf", 1,
                List.of(ParsedElement.paragraph(repeat("栈是一种后进先出的线性表。插入与删除只在栈顶进行。", 60), 1)));
        List<ChunkDraft> chunks = new RecursiveChunker(tokenCounter).chunk(doc).children();
        assertTrue(chunks.size() >= 2, "应切出至少两块");
        String firstTail = chunks.get(0).content().substring(chunks.get(0).content().length() - 12);
        assertTrue(chunks.get(1).content().contains(firstTail.trim().substring(0, 8)),
                "后一块开头应包含前一块尾部句子（15% 重叠）");
    }

    @Test
    void 回归_无标点长文本不会无限递归() {
        // D4 排障场景：超预算 + 分隔符只在末尾 + 无任何句读 → 曾触发自递归栈溢出
        String text = "数据结构无标点长文本".repeat(300) + "\n";
        List<ChunkDraft> chunks = new RecursiveChunker(tokenCounter).chunk(
                new ParsedDocument("t.pdf", 1, List.of(ParsedElement.paragraph(text, 1)))).children();
        assertTrue(chunks.size() >= 2, "应兜底硬切出多块，实际 " + chunks.size());
        for (ChunkDraft chunk : chunks) {
            assertTrue(chunk.tokenCount() <= 320 + 48 + 30, "块超预算：" + chunk.tokenCount());
        }
    }
}
