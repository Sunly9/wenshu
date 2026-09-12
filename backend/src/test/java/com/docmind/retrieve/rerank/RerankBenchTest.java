package com.docmind.retrieve.rerank;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.docmind.index.UnigramTokenizer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** D11 排障基准：不同线程数下 12 对 256 序列的精排耗时（i7-13700H 混合大小核）。
 *  手动执行：mvn test -Dgroups=none -DexcludedGroups= -Dtest=RerankBenchTest（约 4 分钟） */
@Tag("bench")
class RerankBenchTest {

    @Test
    void 线程数扫描基准() throws Exception {
        Path dir = Path.of("models/bge-reranker-base");
        Assumptions.assumeTrue(Files.exists(dir.resolve("model.onnx")), "本机无模型，跳过");

        UnigramTokenizer tokenizer = UnigramTokenizer.fromTokenizerJson(dir.resolve("tokenizer.json"));
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        String passage = "栈是限定仅在表尾进行插入或删除操作的线性表。允许插入和删除的一端称为栈顶，另一端称为栈底。".repeat(6);

        for (int threads : new int[]{1, 4, 6, 10, 20}) {
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(threads);
            try (OrtSession session = env.createSession(dir.resolve("model.onnx").toString(), options)) {
                // 构造 12 对 × 256 序列
                UnigramTokenizer.PairEncoding[] encodings = new UnigramTokenizer.PairEncoding[12];
                int seq = 1;
                for (int i = 0; i < encodings.length; i++) {
                    encodings[i] = tokenizer.encodePair("栈的基本概念", passage, 256);
                    seq = Math.max(seq, encodings[i].ids().length);
                }
                long[][] ids = new long[12][seq];
                long[][] mask = new long[12][seq];
                for (int i = 0; i < 12; i++) {
                    System.arraycopy(encodings[i].ids(), 0, ids[i], 0, encodings[i].ids().length);
                    for (int t = 0; t < encodings[i].ids().length; t++) mask[i][t] = 1;
                }
                // 预热 1 次
                runOnce(env, session, ids, mask);
                // 计时 2 次取最小
                long best = Long.MAX_VALUE;
                for (int r = 0; r < 2; r++) {
                    long t0 = System.currentTimeMillis();
                    runOnce(env, session, ids, mask);
                    best = Math.min(best, System.currentTimeMillis() - t0);
                }
                System.out.printf("[bench] threads=%d → 12对×%d序列 耗时 %dms%n", threads, seq, best);
            }
        }
    }

    private void runOnce(OrtEnvironment env, OrtSession session, long[][] ids, long[][] mask) throws Exception {
        try (OnnxTensor tIds = OnnxTensor.createTensor(env, ids);
             OnnxTensor tMask = OnnxTensor.createTensor(env, mask);
             OrtSession.Result out = session.run(Map.of("input_ids", tIds, "attention_mask", tMask))) {
            float[][] logits = (float[][]) out.get(0).getValue();
            if (logits.length != ids.length) throw new IllegalStateException("输出数量异常");
        }
    }
}
