package com.docmind.quiz;

import java.util.List;

/** 题目（无状态设计：生成结果由前端持有，判分时带回，服务端按 sourceChunkIds 取原文核对） */
public record QuizQuestion(
        String type,            // single / short
        String stem,
        List<String> options,   // single 专用
        String answer,          // single=正确选项字母；short=要点
        String explanation,
        List<Long> sourceChunkIds
) {}
