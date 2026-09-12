package com.docmind.ingest.pipeline;

import com.docmind.domain.Document;
import com.docmind.domain.repo.DocumentRepository;
import com.docmind.ingest.parser.DocumentParser;
import com.docmind.ingest.parser.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 离线链路编排：PENDING → PARSING → INDEXING → READY / FAILED（03 号文档 §4.1）
 * D3：解析器已接入（pdf / md），解析结果记录页数；分片与向量化 D4 接入。
 */
@Component
public class IngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestPipeline.class);

    private final DocumentRepository documentRepo;
    private final IngestProgressStore progress;
    private final List<DocumentParser> parsers;
    private final Path storageDir;

    public IngestPipeline(DocumentRepository documentRepo,
                          IngestProgressStore progress,
                          List<DocumentParser> parsers,
                          @Value("${wenshu.storage-dir}") String storageDir) {
        this.documentRepo = documentRepo;
        this.progress = progress;
        this.parsers = parsers;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
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
            DocumentParser parser = parsers.stream()
                    .filter(p -> p.supports(doc.getFileType()))
                    .findFirst()
                    .orElseThrow(() -> new UnsupportedOperationException(
                            "[" + doc.getFileType() + "] 解析器尚未接入（当前支持 pdf / md，docx 计划 D8）"));

            Path stored = storageDir.resolve("kb-" + doc.getKbId())
                    .resolve(doc.getId() + "." + doc.getFileType());
            ParsedDocument parsed = parser.parse(stored);
            doc.setPageCount(parsed.pageCount());
            documentRepo.save(doc);
            progress.update(documentId, 40);
            log.info("文档 {} 解析完成：{} 页，元素 {} 个（标题 {}，段落 {}，表格 {}，列表 {}）",
                    documentId, parsed.pageCount(), parsed.elements().size(),
                    parsed.countByType(com.docmind.ingest.parser.ParsedElement.ElementType.HEADING),
                    parsed.countByType(com.docmind.ingest.parser.ParsedElement.ElementType.PARAGRAPH),
                    parsed.countByType(com.docmind.ingest.parser.ParsedElement.ElementType.TABLE),
                    parsed.countByType(com.docmind.ingest.parser.ParsedElement.ElementType.LIST));

            // TODO(D4): 分片（父子分块）→ 向量化 → 全文索引 → 批量入库（INDEXING 阶段）
            throw new UnsupportedOperationException("解析成功（" + parsed.pageCount() + " 页 / "
                    + parsed.elements().size() + " 个元素），分片与向量化在 D4 接入，届时请删除后重新上传");
        } catch (Exception e) {
            doc.setStatus(Document.Status.FAILED);
            doc.setErrorMsg(e.getMessage());
            documentRepo.save(doc);
            progress.update(documentId, 100);
            log.info("文档 {} 处理失败：{}", documentId, e.getMessage());
        }
    }
}
