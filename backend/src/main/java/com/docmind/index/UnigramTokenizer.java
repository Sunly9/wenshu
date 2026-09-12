package com.docmind.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unigram(SentencePiece) 分词器：bge-reranker-base(XLM-R 系) 的 tokenizer.json 是
 * [token, score] 数组，非 BERT WordPiece——本类用贪心最长匹配近似 Viterbi 切分
 * （对重排打分足够；词元差异极小）。句对格式遵循 XLM-R：<s> A </s></s> B </s>。
 */
public final class UnigramTokenizer {

    private static final double UNKNOWN_SCORE = -20.0;
    private static final String SPACE = "\u2581";  // ▁

    private final Map<String, Double> scores = new HashMap<>();
    private final Map<String, Long> ids = new HashMap<>();
    private final int maxPieceLen;
    private final long clsId;
    private final long sepId;
    private final long unkId;

    private UnigramTokenizer(Map<String, Long> ids, Map<String, Double> scores, int maxPieceLen) {
        this.ids.putAll(ids);
        this.scores.putAll(scores);
        this.maxPieceLen = maxPieceLen;
        this.clsId = ids.getOrDefault("<s>", 0L);
        this.sepId = ids.getOrDefault("</s>", 2L);
        this.unkId = ids.getOrDefault("<unk>", 3L);
    }

    public static UnigramTokenizer fromTokenizerJson(Path tokenizerJson) throws IOException {
        JsonNode root = new ObjectMapper().readTree(tokenizerJson.toFile());
        JsonNode vocab = root.path("model").path("vocab");
        if (!vocab.isArray() || vocab.isEmpty()) {
            throw new IOException("tokenizer.json 不是 Unigram 词表（model.vocab 非数组）");
        }
        Map<String, Long> ids = new HashMap<>(vocab.size() * 2);
        Map<String, Double> scores = new HashMap<>(vocab.size() * 2);
        int maxLen = 1;
        for (int i = 0; i < vocab.size(); i++) {
            JsonNode entry = vocab.get(i);
            String token = entry.get(0).asText();
            if (ids.putIfAbsent(token, (long) i) == null) {
                scores.put(token, entry.get(1).asDouble());
                maxLen = Math.max(maxLen, token.length());
            }
        }
        return new UnigramTokenizer(ids, scores, Math.min(maxLen, 24));
    }

    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) return tokens;
        String normalized = TokenCounter.sanitize(text).replace(" ", SPACE);
        String padded = SPACE + normalized;
        int i = 0;
        StringBuilder unknown = new StringBuilder();
        while (i < padded.length()) {
            int end = Math.min(i + maxPieceLen, padded.length());
            String hit = null;
            while (end > i) {
                String candidate = padded.substring(i, end);
                if (ids.containsKey(candidate)) {
                    hit = candidate;
                    break;
                }
                end--;
            }
            if (hit == null) {
                // 单字符无法匹配：与后续未知字符合并成一个 <unk>
                unknown.append(padded.charAt(i));
                i++;
                continue;
            }
            if (!unknown.isEmpty()) {
                tokens.add("<unk>");
                unknown.setLength(0);
            }
            tokens.add(hit);
            i = end;
        }
        if (!unknown.isEmpty()) tokens.add("<unk>");
        return tokens;
    }

    public long[] encode(String text, int maxLen) {
        List<Long> out = new ArrayList<>();
        out.add(clsId);
        int budget = maxLen - 2;
        for (String token : tokenize(text)) {
            if (budget-- <= 0) break;
            out.add(ids.getOrDefault(token, unkId));
        }
        out.add(sepId);
        return toArray(out);
    }

    /** XLM-R 句对：<s> query </s></s> passage </s>，typeIds 0/1 分段 */
    public PairEncoding encodePair(String query, String passage, int maxLen) {
        List<String> q = tokenize(query);
        List<String> p = tokenize(passage);
        int budget = maxLen - 4;  // cls + 双 sep + 尾 sep
        int qLen = Math.min(q.size(), Math.max(budget / 2, 16));
        int pLen = Math.min(p.size(), budget - qLen);

        List<Long> ids = new ArrayList<>();
        List<Long> types = new ArrayList<>();
        ids.add(clsId);
        types.add(0L);
        for (int i = 0; i < qLen; i++) {
            ids.add(this.ids.getOrDefault(q.get(i), unkId));
            types.add(0L);
        }
        ids.add(sepId);
        ids.add(sepId);
        types.add(0L);
        types.add(1L);
        for (int i = 0; i < pLen; i++) {
            ids.add(this.ids.getOrDefault(p.get(i), unkId));
            types.add(1L);
        }
        ids.add(sepId);
        types.add(1L);
        return new PairEncoding(toArray(ids), toArray(types));
    }

    public record PairEncoding(long[] ids, long[] typeIds) {}

    private static long[] toArray(List<Long> list) {
        long[] result = new long[list.size()];
        for (int i = 0; i < result.length; i++) result[i] = list.get(i);
        return result;
    }
}
