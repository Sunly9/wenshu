package com.docmind.domain.repo;

import com.docmind.domain.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    Optional<KnowledgeBase> findByShareCode(String shareCode);

    boolean existsByShareCode(String shareCode);

    /** 列表一律按 kb_visitor 绑定关系过滤（03 号文档 §5.4 的隔离查询） */
    @Query(value = """
            SELECT kb.* FROM knowledge_base kb
            JOIN kb_visitor v ON v.kb_id = kb.id
            WHERE v.visitor_id = :visitorId
            ORDER BY kb.created_at DESC
            """, nativeQuery = true)
    List<KnowledgeBase> findAllByVisitor(@Param("visitorId") String visitorId);
}
