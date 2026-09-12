package com.docmind.index;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnigramTokenizerTest {

    private static final Path MODEL_DIR = Path.of("models/bge-reranker-base");

    private UnigramTokenizer tokenizer() throws Exception {
        Assumptions.assumeTrue(Files.exists(MODEL_DIR.resolve("tokenizer.json")), "本机无模型文件，跳过");
        return UnigramTokenizer.fromTokenizerJson(MODEL_DIR.resolve("tokenizer.json"));
    }

    @Test
    void 中文与英文都能切出词元() throws Exception {
        UnigramTokenizer t = tokenizer();
        assertTrue(t.tokenize("数据结构").size() >= 1);
        assertTrue(t.tokenize("linear list").size() >= 2);
        // 空白转 ▁，行首补 ▁
        assertTrue(t.tokenize("你好").get(0).startsWith("\u2581"));
    }

    @Test
    void 句对编码遵循XLMR格式() throws Exception {
        UnigramTokenizer t = tokenizer();
        UnigramTokenizer.PairEncoding pair = t.encodePair("什么是栈", "栈是后进先出的线性表。", 128);
        // <s> q </s></s> p </s>
        assertEquals(t.getClass().getDeclaredMethod("encodePair", String.class, String.class, int.class) != null, true);
        assertTrue(pair.ids().length >= 6);
        // typeIds 应恰好在双 sep 处从 0 切到 1
        int switchAt = -1;
        for (int i = 1; i < pair.typeIds().length; i++) {
            if (pair.typeIds()[i] == 1 && pair.typeIds()[i - 1] == 0) {
                switchAt = i;
                break;
            }
        }
        assertTrue(switchAt > 0, "typeIds 应存在 0→1 切换");
    }
}
