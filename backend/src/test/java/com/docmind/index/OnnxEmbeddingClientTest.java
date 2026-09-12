package com.docmind.index;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnnxEmbeddingClientTest {

    private static final Path MODEL_DIR = Path.of("models/bge-small-zh-v1.5");

    private OnnxEmbeddingClient client() {
        Assumptions.assumeTrue(Files.exists(MODEL_DIR.resolve("model.onnx")), "本机无模型文件，跳过");
        return new OnnxEmbeddingClient(MODEL_DIR.toString());
    }

    @Test
    void 向量维度512_已归一化_语义相近得分更高() {
        OnnxEmbeddingClient client = client();
        float[][] vectors = client.embed(List.of(
                "数据结构研究数据的组织与存储",
                "数据是如何组织和存储的学科",
                "今天晚饭吃了番茄炒蛋"));

        assertEquals(3, vectors.length);
        for (float[] v : vectors) {
            assertEquals(512, v.length);
            double norm = 0;
            for (float f : v) norm += (double) f * f;
            assertEquals(1.0, Math.sqrt(norm), 0.01, "向量应已 L2 归一化");
        }
        double similar = cosine(vectors[0], vectors[1]);
        double dissimilar = cosine(vectors[0], vectors[2]);
        assertTrue(similar > dissimilar,
                "语义相近的句子余弦相似度应更高：" + similar + " vs " + dissimilar);
    }

    @Test
    void 向量字面量格式正确() {
        String literal = ChunkIndexer.toVectorLiteral(new float[]{0.5f, -0.25f, 1f});
        assertEquals("[0.5,-0.25,1.0]", literal);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += (double) a[i] * b[i];
        return dot;  // 已归一化，点积即余弦
    }
}
