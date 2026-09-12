package com.docmind.ingest.pipeline;

import com.docmind.domain.Document;
import com.docmind.domain.KnowledgeBase;
import com.docmind.domain.repo.DocumentRepository;
import com.docmind.domain.repo.KnowledgeBaseRepository;
import com.docmind.ingest.chunker.ChunkDraft;
import com.docmind.ingest.chunker.Chunker;
import com.docmind.ingest.chunker.FixedSizeChunker;
import com.docmind.ingest.chunker.RecursiveChunker;
import com.docmind.ingest.parser.DocumentParser;
import com.docmind.ingest.parser.ParsedDocument;
import com.docmind.index.ChunkIndexer;
import com.docmind.index.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 离线链路编排：PENDING → PARSING → INDEXING → READY / FAILED（03 号文档 §4.1）
 * D4 起全链路贯通：解析 → 分片 → 向量化 → 批量入库。
 */
@Component
public class IngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestPipeline.class);

    private final DocumentRepository documentRepo;
    private final KnowledgeBaseRepository kbRepo;
    private final IngestProgressStore progress;
    private final List<DocumentParser> parsers;
    private final List<Chunker> chunkers;
    private final EmbeddingClient embeddingClient;
    private final ChunkIndexer chunkIndexer;
    private final Path storageDir;

    public IngestPipeline(DocumentRepository documentRepo,
                          KnowledgeBaseRepository kbRepo,
                          IngestProgressStore progress,
                          List<DocumentParser> parsers,
                          List<Chunker> chunkers,
                          EmbeddingClient embeddingClient,
                          ChunkIndexer chunkIndexer,
                          @Value("${wenshu.storage-dir}") String storageDir) {
        this.documentRepo = documentRepo;
        this.kbRepo = kbRepo;
        this.progress = progress;
        this.parsers = parsers;
        this.chunkers = chunkers;
        this.embeddingClient = embeddingClient;
        this.chunkIndexer = chunkIndexer;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @Async("ingestExecutor")
    public void submit(Long documentId) {
        Document doc = documentRepo.findById(documentId).orElse(null);
        if (doc == null) {
            log.warn("文档 {} 不存在，跳过解析", documentId);
            return;
        }
        try {
            ParsedDocument parsed = parseStage(doc);
            int chunkCount = indexingStage(doc, parsed);
            doc.setStatus(Document.Status.READY);
            documentRepo.save(doc);
            progress.update(documentId, 100);
            log.info("文档 {} 就绪：{} 块 / {} token", documentId, chunkCount, doc.getTokenCount());
        } catch (Exception e) {
            doc.setStatus(Document.Status.FAILED);
            doc.setErrorMsg(e.getMessage());
            documentRepo.save(doc);
            progress.update(documentId, 100);
            log.warn("文档 {} 处理失败：{}", documentId, e.getMessage(), e);
        }
    }

    /** PARSING：解析 → ParsedDocument */
    private ParsedDocument parseStage(Document doc) throws Exception {
        doc.setStatus(Document.Status.PARSING);
        documentRepo.save(doc);
        progress.update(doc.getId(), 10);

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
        progress.update(doc.getId(), 40);
        log.info("文档 {} 解析完成：{} 页 / {} 元素", doc.getId(), parsed.pageCount(), parsed.elements().size());
        return parsed;
    }

    /** INDEXING：分片 → 向量化 → 批量入库；@return 块数 */
    private int indexingStage(Document doc, ParsedDocument parsed) {
        doc.setStatus(Document.Status.INDEXING);
        documentRepo.save(doc);
        progress.update(doc.getId(), 50);

        Chunker chunker = chunkerFor(doc.getKbId());
        List<ChunkDraft> drafts = chunker.chunk(parsed);
        if (drafts.isEmpty()) {
            throw new IllegalStateException("解析成功但未产生任何分块（文档可能是空的或全是图片）");
        }
        progress.update(doc.getId(), 60);

        float[][] vectors = embeddingClient.embed(drafts.stream().map(ChunkDraft::content).toList());
        progress.update(doc.getId(), 90);

        chunkIndexer.deleteByDocument(doc.getId());
        chunkIndexer.insertChunks(doc.getId(), drafts, vectors);

        doc.setTokenCount(drafts.stream().mapToInt(ChunkDraft::tokenCount).sum());
        documentRepo.save(doc);
        log.info("文档 {} 入库完成：{} 块 / {} token / 策略 {}",
                doc.getId(), drafts.size(), doc.getTokenCount(), chunker.getClass().getSimpleName());
        return drafts.size();
    }

    private Chunker chunkerFor(Long kbId) {
        String strategy = kbRepo.findById(kbId).map(KnowledgeBase::getChunkStrategy).orElse("RECURSIVE");
        return switch (strategy) {
            case "FIXED" -> chunkers.stream().filter(c -> c instanceof FixedSizeChunker).findFirst().orElseThrow();
            // STRUCTURE_AWARE / SEMANTIC 计划 D8/D9 接入，暂用 RECURSIVE
            default -> chunkers.stream().filter(c -> c instanceof RecursiveChunker).findFirst().orElseThrow();
        };
    }
}
