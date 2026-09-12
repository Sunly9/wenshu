package com.docmind.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotNull(message = "缺少资料库 id") Long kbId,
        @NotBlank(message = "问题不能为空")
        @Size(max = 500, message = "问题最长 500 字符") String question
) {}
