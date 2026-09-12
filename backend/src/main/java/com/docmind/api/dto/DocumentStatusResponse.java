package com.docmind.api.dto;

import com.docmind.domain.Document;

public record DocumentStatusResponse(
        Long documentId,
        String fileName,
        Document.Status status,
        int percent,
        String errorMsg
) {}
