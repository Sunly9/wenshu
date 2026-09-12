package com.docmind.index;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * bge-small-zh-v1.5 CPU 推理（ONNX Runtime + 纯 Java WordPiece 分词器）。
 * 512 维，mean pooling + L2 归一化；batch 32、截断 512 token。
 * 模型约 95MB，懒加载单例（00 号文档 §10："能不用 GPU 跑起来"）。
 */
@Component
public class OnnxEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingClient.class);

    static final int DIM = 512;
    private static final int BATCH = 32;
    private static final int MAX_SEQ = 512;

    private final String modelDir;
    private volatile OrtSession session;
    private volatile BertTokenizer tokenizer;
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();

    public OnnxEmbeddingClient(@Value("${wenshu.embedding.model-dir}") String modelDir) {
        this.modelDir = modelDir;
    }

    @Override
    public float[][] embed(List<String> texts) {
        if (texts.isEmpty()) return new float[0][];
        ensureLoaded();
        float[][] result = new float[texts.size()][];
        for (int from = 0; from < texts.size(); from += BATCH) {
            List<String> batch = texts.subList(from, Math.min(from + BATCH, texts.size()));
            float[][] vectors = runBatch(batch);
            System.arraycopy(vectors, 0, result, from, vectors.length);
        }
        return result;
    }

    private float[][] runBatch(List<String> texts) {
        long[][] idArrays = new long[texts.size()][];
        int seq = 1;
        for (int i = 0; i < texts.size(); i++) {
            idArrays[i] = tokenizer.encode(TokenCounter.sanitize(texts.get(i)), MAX_SEQ);
            seq = Math.max(seq, idArrays[i].length);
        }
        long[][] ids = new long[texts.size()][seq];
        long[][] mask = new long[texts.size()][seq];
        long[][] typeIds = new long[texts.size()][seq];
        for (int i = 0; i < texts.size(); i++) {
            System.arraycopy(idArrays[i], 0, ids[i], 0, idArrays[i].length);
            for (int t = 0; t < idArrays[i].length; t++) {
                mask[i][t] = 1;
            }
        }

        try (OnnxTensor tIds = OnnxTensor.createTensor(env, ids);
             OnnxTensor tMask = OnnxTensor.createTensor(env, mask);
             OnnxTensor tTypes = OnnxTensor.createTensor(env, typeIds);
             OrtSession.Result output = session.run(Map.of(
                     inputName("input_ids"), tIds,
                     inputName("attention_mask"), tMask,
                     inputName("token_type_ids"), tTypes))) {

            float[][][] hidden = (float[][][]) output.get(0).getValue();  // [b, seq, 512]
            return meanPoolAndNormalize(hidden, mask);
        } catch (Exception e) {
            throw new IllegalStateException("ONNX 向量化推理失败：" + e.getMessage(), e);
        }
    }

    /** mean pooling（按 attention_mask 加权）+ L2 归一化 */
    static float[][] meanPoolAndNormalize(float[][][] hidden, long[][] mask) {
        int batch = hidden.length;
        float[][] pooled = new float[batch][];
        for (int i = 0; i < batch; i++) {
            float[] acc = new float[DIM];
            double count = 0;
            for (int t = 0; t < mask[i].length && t < hidden[i].length; t++) {
                if (mask[i][t] == 0) continue;
                count++;
                for (int d = 0; d < DIM; d++) {
                    acc[d] += hidden[i][t][d];
                }
            }
            double norm = 0;
            for (int d = 0; d < DIM; d++) {
                acc[d] = (float) (acc[d] / Math.max(count, 1));
                norm += (double) acc[d] * acc[d];
            }
            norm = Math.sqrt(Math.max(norm, 1e-12));
            for (int d = 0; d < DIM; d++) {
                acc[d] = (float) (acc[d] / norm);
            }
            pooled[i] = acc;
        }
        return pooled;
    }

    private String inputName(String canonical) {
        return session.getInputNames().contains(canonical) ? canonical
                : session.getInputNames().iterator().next();
    }

    private synchronized void ensureLoaded() {
        if (session != null) return;
        try {
            Path dir = Path.of(modelDir);
            this.tokenizer = BertTokenizer.fromTokenizerJson(dir.resolve("tokenizer.json"));
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(Math.max(Runtime.getRuntime().availableProcessors() / 2, 2));
            this.session = env.createSession(dir.resolve("model.onnx").toString(), options);
            log.info("bge-small-zh-v1.5 已加载：inputs={}, outputs={}",
                    session.getInputNames(), session.getOutputNames());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "嵌入模型加载失败，请确认模型文件在 " + modelDir + " 下（model.onnx + tokenizer.json）：" + e.getMessage(), e);
        }
    }
}
