package com.docmind.index;

import com.docmind.ingest.chunker.ChunkDraft;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** chunk 批量入库（pgvector 向量以字符串字面量 ?::vector 写入；父块/全文索引 D8/D10 接入） */
@Component
public class ChunkIndexer {

    private final JdbcTemplate jdbc;

    public ChunkIndexer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void deleteByDocument(long documentId) {
        jdbc.update("DELETE FROM chunk WHERE document_id = ?", documentId);
    }

    public void insertChunks(long documentId, List<ChunkDraft> chunks, float[][] vectors) {
        if (chunks.size() != vectors.length) {
            throw new IllegalStateException("分块数与向量数不一致：" + chunks.size() + " vs " + vectors.length);
        }
        List<Object[]> args = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkDraft chunk = chunks.get(i);
            args.add(new Object[]{documentId, chunk.content(), chunk.tokenCount(),
                    chunk.sectionPath(), chunk.pageNo(), i, toVectorLiteral(vectors[i])});
        }
        jdbc.batchUpdate("""
                INSERT INTO chunk(document_id, content, token_count, section_path, page_no, chunk_index, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?::vector)
                """, args);
    }

    /** float[] → "[0.12,0.34,...]"，pgvector 接受该字面量 */
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 9).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
