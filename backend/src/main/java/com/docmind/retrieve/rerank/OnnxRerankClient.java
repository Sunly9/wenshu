package com.docmind.retrieve.rerank;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.docmind.index.TokenCounter;
import com.docmind.index.UnigramTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * bge-reranker-base 交叉编码器精排（ONNX CPU）。
 * 当前加载 int8 量化版（279MB）——fp32(1.1GB) 对 30 候选 CPU 精排需 2~3s，超出预算；
 * 换 fp32 只需替换 models/bge-reranker-base/model.onnx 文件，代码不变。
 */
@Component
public class OnnxRerankClient implements RerankClient {

    private static final Logger log = LoggerFactory.getLogger(OnnxRerankClient.class);

    private static final int MAX_SEQ = 192;  // 问题+段落核心足够；序列对耗时近似线性
    private static final int BATCH = 10;
    /** 基准实测（i7-13700H, 12对×207seq）：1线程24.8s / 4线程5.5s / 6线程3.2s / 20线程29.8s（E核争用） */
    private static final int THREADS = 6;

    private final String modelDir;
    private volatile OrtSession session;
    private volatile UnigramTokenizer tokenizer;  // bge-reranker 为 XLM-R 系 Unigram 分词
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();

    public OnnxRerankClient(@Value("${wenshu.rerank.model-dir}") String modelDir) {
        this.modelDir = modelDir;
    }

    @Override
    public double[] score(String query, List<String> passages) {
        if (passages.isEmpty()) return new double[0];
        ensureLoaded();
        double[] scores = new double[passages.size()];
        for (int from = 0; from < passages.size(); from += BATCH) {
            List<String> batch = passages.subList(from, Math.min(from + BATCH, passages.size()));
            double[] batchScores = runBatch(query, batch);
            System.arraycopy(batchScores, 0, scores, from, batchScores.length);
        }
        return scores;
    }

    private double[] runBatch(String query, List<String> passages) {
        UnigramTokenizer.PairEncoding[] encodings = new UnigramTokenizer.PairEncoding[passages.size()];
        int seq = 1;
        for (int i = 0; i < passages.size(); i++) {
            String passage = TokenCounter.sanitize(passages.get(i));
            encodings[i] = tokenizer.encodePair(TokenCounter.sanitize(query), passage, MAX_SEQ);
            seq = Math.max(seq, encodings[i].ids().length);
        }
        long[][] ids = new long[passages.size()][seq];
        long[][] mask = new long[passages.size()][seq];
        long[][] typeIds = new long[passages.size()][seq];
        for (int i = 0; i < encodings.length; i++) {
            long[] id = encodings[i].ids();
            long[] type = encodings[i].typeIds();
            System.arraycopy(id, 0, ids[i], 0, id.length);
            System.arraycopy(type, 0, typeIds[i], 0, type.length);
            for (int t = 0; t < id.length; t++) mask[i][t] = 1;
        }

        try (OnnxTensor tIds = OnnxTensor.createTensor(env, ids);
             OnnxTensor tMask = OnnxTensor.createTensor(env, mask);
             OnnxTensor tTypes = OnnxTensor.createTensor(env, typeIds)) {
            // XLM-R 系模型只收 input_ids+attention_mask（无 token_type_ids），按会话声明动态组装
            Map<String, OnnxTensor> inputs = new java.util.HashMap<>();
            inputs.put("input_ids", tIds);
            inputs.put("attention_mask", tMask);
            if (session.getInputNames().contains("token_type_ids")) {
                inputs.put("token_type_ids", tTypes);
            }
            try (OrtSession.Result output = session.run(inputs)) {
                float[][] logits = (float[][]) output.get(0).getValue();  // [b, 1]
                double[] scores = new double[logits.length];
                for (int i = 0; i < logits.length; i++) {
                    scores[i] = sigmoid(logits[i][0]);
                }
                return scores;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Rerank 推理失败：" + e.getMessage(), e);
        }
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private synchronized void ensureLoaded() {
        if (session != null) return;
        try {
            Path dir = Path.of(modelDir);
            this.tokenizer = UnigramTokenizer.fromTokenizerJson(dir.resolve("tokenizer.json"));
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(THREADS);
            this.session = env.createSession(dir.resolve("model.onnx").toString(), options);
            log.info("bge-reranker-base 已加载（int8 量化）: inputs={}", session.getInputNames());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Rerank 模型加载失败，请确认 " + modelDir + " 下有 model.onnx + tokenizer.json：" + e.getMessage(), e);
        }
    }
}
