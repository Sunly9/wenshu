package com.docmind.api.dto;

import com.docmind.domain.Document;

public record DocumentUploadResponse(Long documentId, Document.Status status) {}
