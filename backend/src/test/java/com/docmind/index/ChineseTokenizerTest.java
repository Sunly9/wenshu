package com.docmind.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChineseTokenizerTest {

    @Test
    void 中文分词_产出空格分隔词元() {
        String segmented = new ChineseTokenizer().segment("数据结构是计算机学科的核心基础课程");
        assertFalse(segmented.isBlank());
        assertTrue(segmented.contains("数据"), "应切出'数据'，实际：" + segmented);
        assertTrue(segmented.contains("计算机"), "应切出'计算机'，实际：" + segmented);
    }

    @Test
    void 空文本与控制字符_安全返回() {
        assertTrue(new ChineseTokenizer().segment("").isEmpty());
        assertFalse(new ChineseTokenizer().segment("数据\u0000结构").contains("\u0000"));
    }
}
