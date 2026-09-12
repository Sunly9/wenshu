package com.docmind.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KbCreateRequest(
        @NotBlank(message = "库名不能为空")
        @Size(max = 64, message = "库名最长 64 字符")
        String name,

        @Size(max = 255, message = "描述最长 255 字符")
        String description
) {}
