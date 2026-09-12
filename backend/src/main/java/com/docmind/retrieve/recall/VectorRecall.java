package com.docmind.retrieve.recall;

import com.docmind.index.ChunkIndexer;
import com.docmind.index.EmbeddingClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 纯向量召回：问题向量化后按余弦相似度取 topK（M1；双路召回与 RRF 在 D10 接入） */
@Component
public class VectorRecall {

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddingClient;

    public VectorRecall(JdbcTemplate jdbc, EmbeddingClient embeddingClient) {
        this.jdbc = jdbc;
        this.embeddingClient = embeddingClient;
    }

    public List<RetrievedChunk> recall(long kbId, String question, int topK) {
        float[] queryVector = embeddingClient.embed(List.of(question))[0];
        String vec = ChunkIndexer.toVectorLiteral(queryVector);
        return jdbc.query("""
                SELECT c.id, c.document_id, c.parent_id, d.file_name, c.section_path, c.page_no, c.content, c.token_count,
                       1 - (c.embedding <=> ?::vector) AS score
                FROM chunk c
                JOIN document d ON d.id = c.document_id
                JOIN knowledge_base kb ON kb.id = d.kb_id
                WHERE d.kb_id = ? AND d.status = 'READY' AND c.embedding IS NOT NULL
                  AND c.strategy = kb.chunk_strategy
                ORDER BY c.embedding <=> ?::vector
                LIMIT ?
                """,
                (rs, i) -> new RetrievedChunk(
                        rs.getLong("id"),
                        rs.getLong("document_id"),
                        (Long) rs.getObject("parent_id"),
                        rs.getString("file_name"),
                        rs.getString("section_path"),
                        rs.getObject("page_no") == null ? null : rs.getInt("page_no"),
                        rs.getString("content"),
                        rs.getInt("token_count"),
                        rs.getDouble("score")),
                vec, kbId, vec, topK);
    }
}
