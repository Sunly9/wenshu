package com.docmind.generation;

import com.docmind.retrieve.recall.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {

    @Test
    void 上下文带编号出处_问题在末尾() {
        RetrievedChunk chunk = new RetrievedChunk(1, 1, null, "数据结构.pdf",
                "第2章 线性表", 28, "线性表是 n 个数据元素的有限序列。", 30, 0.9);
        String prompt = new PromptBuilder().buildUserPrompt("什么是线性表？", List.of(chunk));

        assertTrue(prompt.contains("[1] 来源: 数据结构.pdf · 第2章 线性表 · 第28页"));
        assertTrue(prompt.contains("线性表是 n 个数据元素的有限序列。"));
        assertTrue(prompt.trim().endsWith("问题：什么是线性表？"));
        assertTrue(PromptBuilder.SYSTEM_PROMPT.contains("[n]"));
        assertTrue(PromptBuilder.SYSTEM_PROMPT.contains("未找到依据"));
    }
}
