package com.docmind.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ChatRequest(
        @NotNull(message = "缺少资料库 id") Long kbId,
        @NotBlank(message = "问题不能为空")
        @Size(max = 500, message = "问题最长 500 字符") String question,
        String mode,          // strict(默认) / learn
        List<HistoryItem> history  // 最近几轮对话，支持追问
) {
    public record HistoryItem(String role, String content) {}
}
