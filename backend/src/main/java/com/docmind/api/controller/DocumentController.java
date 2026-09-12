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
    private final com.docmind.service.KbService kbService;
    private final com.docmind.service.ChunkPreviewService previewService;

    public DocumentController(DocumentService documentService,
                              com.docmind.service.KbService kbService,
                              com.docmind.service.ChunkPreviewService previewService) {
        this.documentService = documentService;
        this.kbService = kbService;
        this.previewService = previewService;
    }

    @PostMapping("/api/kb/{kbId}/documents")
    public DocumentUploadResponse upload(@PathVariable Long kbId,
                                         @RequestHeader("X-Visitor-Id")
                                         @NotBlank(message = "缺少访客标识")
                                         @Size(max = 64) String visitorId,
                                         @RequestParam("file") MultipartFile file) {
        return documentService.upload(kbId, visitorId, file);
    }

    @GetMapping("/api/kb/{kbId}/documents")
    public java.util.List<DocumentStatusResponse> list(@PathVariable Long kbId,
                                                       @RequestHeader("X-Visitor-Id")
                                                       @NotBlank(message = "缺少访客标识")
                                                       @Size(max = 64) String visitorId) {
        return documentService.listByKb(kbId, visitorId);
    }

    /** 文件流（PDF 查看器）：带访问校验地返回原始文件 */
    @GetMapping("/api/documents/{id}/file")
    public org.springframework.http.ResponseEntity<byte[]> file(@PathVariable Long id,
                                                               @RequestHeader("X-Visitor-Id")
                                                               @NotBlank(message = "缺少访客标识")
                                                               @Size(max = 64) String visitorId) throws java.io.IOException {
        java.nio.file.Path path = documentService.storedFile(id, visitorId);
        byte[] bytes = java.nio.file.Files.readAllBytes(path);
        String name = path.getFileName().toString().toLowerCase();
        String media = name.endsWith(".pdf") ? "application/pdf"
                : name.endsWith(".md") ? "text/markdown; charset=utf-8" : "application/octet-stream";
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", media)
                .body(bytes);
    }

    /** 分片预览：传策略与文件，不入库，直接返回切分结果（00 号文档 §7） */
    @PostMapping("/api/kb/{kbId}/chunks/preview")
    public com.docmind.api.dto.ChunkPreviewResponse previewChunks(
            @PathVariable Long kbId,
            @RequestHeader("X-Visitor-Id")
            @NotBlank(message = "缺少访客标识")
            @Size(max = 64) String visitorId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "strategy", defaultValue = "STRUCTURE_AWARE") String strategy) {
        kbService.requireAccessible(kbId, visitorId);
        return previewService.preview(strategy, file);
    }

    @GetMapping("/api/documents/{id}/status")
    public DocumentStatusResponse status(@PathVariable Long id,
                                         @RequestHeader("X-Visitor-Id")
                                         @NotBlank(message = "缺少访客标识")
                                         @Size(max = 64) String visitorId) {
        return documentService.status(id, visitorId);
    }
}
