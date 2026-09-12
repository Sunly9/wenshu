package com.docmind.domain.repo;

import com.docmind.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    long countByKbId(Long kbId);

    List<Document> findByKbIdOrderByCreatedAtDesc(Long kbId);
}
