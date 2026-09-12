package com.docmind.ingest.pipeline;

import com.docmind.domain.Document;
import com.docmind.domain.repo.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 离线链路编排：PENDING → PARSING → INDEXING → READY / FAILED（03 号文档 §4.1）
 * D2 为状态机空跑版：解析器尚未接入，走到 PARSING 后以明确的错误信息落 FAILED。
 * D3/D4 将把解析与分片真正接进来。
 */
@Component
public class IngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestPipeline.class);

    private final DocumentRepository documentRepo;
    private final IngestProgressStore progress;

    public IngestPipeline(DocumentRepository documentRepo, IngestProgressStore progress) {
        this.documentRepo = documentRepo;
        this.progress = progress;
    }

    @Async("ingestExecutor")
    public void submit(Long documentId) {
        Document doc = documentRepo.findById(documentId).orElse(null);
        if (doc == null) {
            log.warn("文档 {} 不存在，跳过解析", documentId);
            return;
        }
        doc.setStatus(Document.Status.PARSING);
        documentRepo.save(doc);
        progress.update(documentId, 10);

        try {
            // TODO(D3): 按 fileType 调用 PdfParser / MarkdownParser
            // TODO(D4): 分片 → 向量化 → 批量入库（INDEXING 阶段）
            throw new UnsupportedOperationException(
                    "[" + doc.getFileType() + "] 解析器尚未接入（D3 实现），文档已标记为失败，届时请删除后重新上传");
        } catch (Exception e) {
            doc.setStatus(Document.Status.FAILED);
            doc.setErrorMsg(e.getMessage());
            documentRepo.save(doc);
            progress.update(documentId, 100);
            log.info("文档 {} 处理失败：{}", documentId, e.getMessage());
        }
    }
}
