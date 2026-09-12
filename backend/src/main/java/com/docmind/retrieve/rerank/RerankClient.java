package com.docmind.retrieve.rerank;

import java.util.List;

/** 重排客户端（交叉编码器，逐对打分） */
public interface RerankClient {

    /** @return 与 passages 等长的 0~1 分数（sigmoid 归一） */
    double[] score(String query, List<String> passages);
}
