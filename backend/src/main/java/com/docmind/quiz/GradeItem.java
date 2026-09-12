package com.docmind.quiz;

import java.util.List;

/** 判分结果：单选判对错；简答由 LLM 依据原文打分并指出漏掉的句子 */
public record GradeItem(
        int index,
        String type,
        Boolean correct,        // single 专用
        int score,              // 0/100（single）或 0~100（short）
        String comment,
        List<String> missedSentences
) {}
