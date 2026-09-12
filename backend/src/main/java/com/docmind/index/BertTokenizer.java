package com.docmind.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 纯 Java 的 BERT WordPiece 分词器（中文按字切、英文按词再 WordPiece、标点独立成 token）。
 * 直接解析 bge 的 tokenizer.json（model.vocab），不再依赖 DJL 的 Rust 分词库——
 * 后者在真实语料上会因回调异常 panic 并杀死 JVM（D4 排障记录）。
 */
public final class BertTokenizer {

    private static final Pattern HAN = Pattern.compile("([\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF])");
    private static final int MAX_WORD_CHARS = 100;

    private final Map<String, Long> vocab;
    private final long clsId;
    private final long sepId;
    private final long unkId;
    private final boolean lowercase;

    private BertTokenizer(Map<String, Long> vocab, boolean lowercase) {
        this.vocab = vocab;
        this.lowercase = lowercase;
        this.clsId = require(vocab, "[CLS]");
        this.sepId = require(vocab, "[SEP]");
        this.unkId = require(vocab, "[UNK]");
    }

    public static BertTokenizer fromTokenizerJson(Path tokenizerJson) throws IOException {
        JsonNode root = new ObjectMapper().readTree(tokenizerJson.toFile());
        JsonNode vocabNode = root.path("model").path("vocab");
        if (!vocabNode.isObject()) {
            throw new IOException("tokenizer.json 中没有 model.vocab，无法初始化分词器");
        }
        Map<String, Long> vocab = new HashMap<>(vocabNode.size() * 2);
        for (Iterator<Map.Entry<String, JsonNode>> it = vocabNode.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            vocab.put(e.getKey(), e.getValue().asLong());
        }
        boolean lowercase = root.path("normalizer").toString().contains("\"lowercase\":true");
        return new BertTokenizer(vocab, lowercase);
    }

    /** 编码为 token id 序列：[CLS] + tokens(截断到 maxLen-2) + [SEP] */
    public long[] encode(String text, int maxLen) {
        List<Long> ids = new ArrayList<>();
        ids.add(clsId);
        int budget = maxLen - 2;
        for (String token : tokenize(text)) {
            if (budget-- <= 0) break;
            ids.add(vocab.getOrDefault(token, unkId));
        }
        ids.add(sepId);
        return toArray(ids);
    }

    /**
     * 句对编码（交叉编码器输入）：[CLS] query [SEP] passage [SEP]，
     * typeIds 前 3 段为 0、passage 段为 1。截断优先保 passage 尾部？——保 query 完整，passage 截断。
     */
    public PairEncoding encodePair(String query, String passage, int maxLen) {
        List<String> qTokens = tokenize(query);
        List<String> pTokens = tokenize(passage);
        int budget = maxLen - 3;  // cls + 2*sep
        int qLen = Math.min(qTokens.size(), Math.max(budget / 2, 16));
        int pLen = Math.min(pTokens.size(), budget - qLen);

        List<Long> ids = new ArrayList<>();
        List<Long> typeIds = new ArrayList<>();
        ids.add(clsId);
        typeIds.add(0L);
        for (int i = 0; i < qLen; i++) {
            ids.add(vocab.getOrDefault(qTokens.get(i), unkId));
            typeIds.add(0L);
        }
        ids.add(sepId);
        typeIds.add(0L);
        for (int i = 0; i < pLen; i++) {
            ids.add(vocab.getOrDefault(pTokens.get(i), unkId));
            typeIds.add(1L);
        }
        ids.add(sepId);
        typeIds.add(1L);
        return new PairEncoding(toArray(ids), toArray(typeIds));
    }

    public record PairEncoding(long[] ids, long[] typeIds) {}

    private static long[] toArray(List<Long> ids) {
        long[] result = new long[ids.size()];
        for (int i = 0; i < result.length; i++) result[i] = ids.get(i);
        return result;
    }

    List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return List.of();
        String cleaned = TokenCounter.sanitize(text);
        if (lowercase) cleaned = cleaned.toLowerCase();
        // BERT 中文规约：汉字两侧补空格，使其独立成 token
        cleaned = HAN.matcher(cleaned).replaceAll(" $1 ");

        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (String chunk : cleaned.split("\\s+")) {
            if (chunk.isEmpty()) continue;
            for (int i = 0; i < chunk.length(); i++) {
                char c = chunk.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    word.append(c);
                } else {
                    flushWord(word, tokens);
                    tokens.add(String.valueOf(c));  // 标点独立成 token（BERT 规范）
                }
            }
            flushWord(word, tokens);
        }
        return tokens;
    }

    private void flushWord(StringBuilder word, List<String> out) {
        if (word.isEmpty()) return;
        if (word.length() > MAX_WORD_CHARS) {
            out.add("[UNK]");
        } else {
            wordPiece(word.toString(), out);
        }
        word.setLength(0);
    }

    /** 贪心最长匹配；词内找不到继续以 ## 前缀匹配；整体失败则 [UNK] */
    private void wordPiece(String word, List<String> out) {
        int start = 0;
        List<String> pieces = new ArrayList<>();
        while (start < word.length()) {
            int end = word.length();
            String hit = null;
            while (start < end) {
                String candidate = (pieces.isEmpty() ? "" : "##") + word.substring(start, end);
                if (vocab.containsKey(candidate)) {
                    hit = candidate;
                    break;
                }
                end--;
            }
            if (hit == null) {
                out.add("[UNK]");
                return;
            }
            pieces.add(hit);
            start = end;
        }
        // 连续的字母/数字作为整词走 WordPiece；标点等单字符本就独立
        out.addAll(pieces);
    }

    private static long require(Map<String, Long> vocab, String token) {
        Long id = vocab.get(token);
        if (id == null) {
            throw new IllegalStateException("vocab 中缺少特殊 token " + token);
        }
        return id;
    }
}
