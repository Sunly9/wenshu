package com.docmind.eval;

/** 评测题录入请求（D17 标注工具；表为 DDL 冻结的 eval_question） */
public record EvalQuestionRequest(
        String question,
        String type,             // FACT / MULTI_HOP / TABLE / NO_ANSWER
        java.util.List<Long> goldChunkIds,   // NO_ANSWER 为空
        String goldAnswer
) {}
