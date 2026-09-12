package com.docmind.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShareCodeGeneratorTest {

    @Test
    void 长度为6_且不含易混淆字符() {
        for (int i = 0; i < 1000; i++) {
            String code = ShareCodeGenerator.generate();
            assertEquals(6, code.length());
            for (char c : code.toCharArray()) {
                assertTrue("23456789ABCDEFGHJKLMNPQRSTUVWXYZ".indexOf(c) >= 0,
                        "出现了易混淆字符: " + c);
            }
        }
    }

    @Test
    void 大样本下基本不碰撞() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            codes.add(ShareCodeGenerator.generate());
        }
        // 32^6≈10.7亿，5000 次期望碰撞约 0.01 次；万一碰撞由服务层循环兜底
        assertEquals(5000, codes.size());
    }
}
