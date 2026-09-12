package com.docmind.index;

import java.util.List;

/** 向量化客户端（bge-small-zh-v1.5，512 维，CPU ONNX） */
public interface EmbeddingClient {

    /** @return 与输入等长且已 L2 归一化的向量数组 */
    float[][] embed(List<String> texts);
}
