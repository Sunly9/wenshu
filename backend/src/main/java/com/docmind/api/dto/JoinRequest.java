package com.docmind.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinRequest(
        @NotBlank(message = "口令不能为空")
        @Size(min = 6, max = 6, message = "口令为 6 位")
        String code
) {}
