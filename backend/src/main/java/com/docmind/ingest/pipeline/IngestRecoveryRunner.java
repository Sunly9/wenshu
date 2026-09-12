package com.docmind.ingest.pipeline;

import com.docmind.domain.Document;
import com.docmind.domain.repo.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 服务重启恢复（03 号文档 §4.1）：启动时把所有非终态文档统一置 FAILED。
 * 解析在进程内存中执行，重启即中断；诚实标记好过永远卡在"解析中"。
 */
@Component
public class IngestRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRecoveryRunner.class);

    private final DocumentRepository documentRepo;

    public IngestRecoveryRunner(DocumentRepository documentRepo) {
        this.documentRepo = documentRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Document> interrupted = documentRepo.findAll().stream()
                .filter(d -> d.getStatus() == Document.Status.PENDING
                        || d.getStatus() == Document.Status.PARSING
                        || d.getStatus() == Document.Status.INDEXING)
                .toList();
        for (Document doc : interrupted) {
            doc.setStatus(Document.Status.FAILED);
            doc.setErrorMsg("服务重启导致解析中断，请删除后重新上传");
            documentRepo.save(doc);
        }
        if (!interrupted.isEmpty()) {
            log.warn("启动恢复：{} 份中断文档已标记为 FAILED", interrupted.size());
        }
    }
}
