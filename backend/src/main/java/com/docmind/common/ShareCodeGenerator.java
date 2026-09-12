package com.docmind.common;

import java.security.SecureRandom;

/** 库口令：6 位大写字母+数字，剔除易混淆的 0 O 1 I（00 号文档 §5） */
public final class ShareCodeGenerator {

    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int LENGTH = 6;

    private ShareCodeGenerator() {}

    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
