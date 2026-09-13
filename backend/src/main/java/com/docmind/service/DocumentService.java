package com.docmind.service;

import com.docmind.api.dto.DocumentStatusResponse;
import com.docmind.api.dto.DocumentUploadResponse;
import com.docmind.common.exception.ApiException;
import com.docmind.common.exception.NotFoundException;
import com.docmind.domain.Document;
import com.docmind.domain.repo.DocumentRepository;
import com.docmind.ingest.pipeline.IngestPipeline;
import com.docmind.ingest.pipeline.IngestProgressStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class DocumentService {

    /** 00 号文档 §6：单文档 50MB；§2：单库 100 文档 */
    static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;
    static final int MAX_DOCS_PER_KB = 100;
    static final Set<String> ALLOWED_TYPES = Set.of("pdf", "docx", "md", "markdown");

    private final DocumentRepository documentRepo;
    private final KbService kbService;
    private final IngestPipeline pipeline;
    private final IngestProgressStore progress;
    private final Path storageDir;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public DocumentService(DocumentRepository documentRepo,
                           KbService kbService,
                           IngestPipeline pipeline,
                           IngestProgressStore progress,
                           org.springframework.jdbc.core.JdbcTemplate jdbc,
                           @Value("${wenshu.storage-dir}") String storageDir) throws IOException {
        this.documentRepo = documentRepo;
        this.kbService = kbService;
        this.pipeline = pipeline;
        this.progress = progress;
        this.jdbc = jdbc;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
        Files.createDirectories(this.storageDir);
    }

    public DocumentUploadResponse upload(Long kbId, String visitorId, MultipartFile file) {
        kbService.requireAccessible(kbId, visitorId);

        if (file == null || file.isEmpty()) {
            throw new ApiException("请选择要上传的文件");
        }
        if (documentRepo.countByKbId(kbId) >= MAX_DOCS_PER_KB) {
            throw new ApiException("该资料库已达 100 份文档上限，请先删除部分资料");
        }
        String originalName = basename(file.getOriginalFilename());
        String ext = extensionOf(originalName);
        if (!ALLOWED_TYPES.contains(ext)) {
            throw new ApiException("仅支持 PDF / Word(.docx) / Markdown 文件，不支持 ." + ext);
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ApiException(413, "单个文档不能超过 50MB");
        }

        Document doc = new Document();
        doc.setKbId(kbId);
        doc.setFileName(originalName);
        doc.setFileType(ext.equals("markdown") ? "md" : ext);
        doc.setSizeBytes(file.getSize());
        doc.setStatus(Document.Status.PENDING);
        doc = documentRepo.save(doc);

        try {
            Path dir = storageDir.resolve("kb-" + kbId);
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), dir.resolve(doc.getId() + "." + doc.getFileType()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            doc.setStatus(Document.Status.FAILED);
            doc.setErrorMsg("文件保存失败：" + e.getMessage());
            documentRepo.save(doc);
            throw new ApiException("文件保存失败，请重试");
        }

        pipeline.submit(doc.getId());
        return new DocumentUploadResponse(doc.getId(), doc.getStatus());
    }

    public DocumentStatusResponse status(Long documentId, String visitorId) {
        Document doc = documentRepo.findById(documentId)
                .orElseThrow(() -> new NotFoundException("文档不存在"));
        kbService.requireAccessible(doc.getKbId(), visitorId);  // 防跨库越权查询
        return toStatusResponse(doc);
    }

    public List<DocumentStatusResponse> listByKb(Long kbId, String visitorId) {
        kbService.requireAccessible(kbId, visitorId);
        return documentRepo.findByKbIdOrderByCreatedAtDesc(kbId).stream()
                .map(this::toStatusResponse)
                .toList();
    }

    private DocumentStatusResponse toStatusResponse(Document doc) {
        return new DocumentStatusResponse(
                doc.getId(),
                doc.getFileName(),
                doc.getStatus(),
                percentOf(doc),
                doc.getErrorMsg()
        );
    }

    /** 状态对应的展示进度（03 号文档 §4.1：PARSING 40% + INDEXING 60%） */
    private int percentOf(Document doc) {
        return switch (doc.getStatus()) {
            case PENDING -> 5;
            case PARSING -> Math.max(10, progress.get(doc.getId()));
            case INDEXING -> Math.max(80, progress.get(doc.getId()));
            case READY, FAILED -> 100;
        };
    }

    /** 文件流（PDF 查看器用）：校验访问权后返回落盘路径 */
    public Path storedFile(long documentId, String visitorId) {
        Document doc = documentRepo.findById(documentId)
                .orElseThrow(() -> new NotFoundException("文档不存在"));
        kbService.requireAccessible(doc.getKbId(), visitorId);
        return storageDir.resolve("kb-" + doc.getKbId())
                .resolve(doc.getId() + "." + doc.getFileType());
    }

    /** 原文阅读卡数据：块全文 + 父块 + 前后相邻子块（引用/定位点击的落地内容） */
    public java.util.Map<String, Object> chunkRow(long chunkId, String visitorId) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id, c.document_id, c.parent_id, c.content, c.section_path, c.page_no,
                       c.chunk_index, d.file_name, d.kb_id
                FROM chunk c JOIN document d ON d.id = c.document_id
                WHERE c.id = ?
                """, chunkId);
        if (rows.isEmpty()) {
            throw new NotFoundException("内容块不存在");
        }
        var row = rows.get(0);
        kbService.requireAccessible(((Number) row.get("kb_id")).longValue(), visitorId);

        java.util.Map<String, Object> result = new java.util.HashMap<>(row);
        Object parentId = row.get("parent_id");
        if (parentId != null) {
            List<java.util.Map<String, Object>> parents = jdbc.queryForList(
                    "SELECT content, page_no, section_path FROM chunk WHERE id = ?", ((Number) parentId).longValue());
            if (!parents.isEmpty()) {
                result.put("parentContent", parents.get(0).get("content"));
            }
        }
        long documentId = ((Number) row.get("document_id")).longValue();
        int idx = ((Number) row.get("chunk_index")).intValue();
        List<java.util.Map<String, Object>> neighbors = jdbc.queryForList("""
                SELECT chunk_index, content FROM chunk
                WHERE document_id = ? AND parent_id IS NOT NULL AND chunk_index IN (?, ?)
                """, documentId, idx - 1, idx + 1);
        for (var n : neighbors) {
            int nIdx = ((Number) n.get("chunk_index")).intValue();
            result.put(nIdx < idx ? "prevContent" : "nextContent", n.get("content"));
        }
        return result;
    }

    /** 防御：部分客户端上传的文件名带完整路径，只取最后一段 */
    private String basename(String name) {
        if (name == null || name.isBlank()) return "";
        String normalized = name.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new ApiException("文件没有扩展名，无法识别类型");
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
