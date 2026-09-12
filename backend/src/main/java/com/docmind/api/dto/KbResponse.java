package com.docmind.api.dto;

import java.time.Instant;

public record KbResponse(
        Long id,
        String name,
        String description,
        String shareCode,
        boolean owner,
        long documentCount,
        Instant createdAt
) {}
