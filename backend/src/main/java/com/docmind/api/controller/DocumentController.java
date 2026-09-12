package com.docmind.api.controller;

import com.docmind.api.dto.DocumentStatusResponse;
import com.docmind.api.dto.DocumentUploadResponse;
import com.docmind.service.DocumentService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Validated
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/api/kb/{kbId}/documents")
    public DocumentUploadResponse upload(@PathVariable Long kbId,
                                         @RequestHeader("X-Visitor-Id")
                                         @NotBlank(message = "缺少访客标识")
                                         @Size(max = 64) String visitorId,
                                         @RequestParam("file") MultipartFile file) {
        return documentService.upload(kbId, visitorId, file);
    }

    @GetMapping("/api/documents/{id}/status")
    public DocumentStatusResponse status(@PathVariable Long id,
                                         @RequestHeader("X-Visitor-Id")
                                         @NotBlank(message = "缺少访客标识")
                                         @Size(max = 64) String visitorId) {
        return documentService.status(id, visitorId);
    }
}
