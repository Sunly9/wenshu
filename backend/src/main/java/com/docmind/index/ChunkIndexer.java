package com.docmind.index;

import com.docmind.ingest.chunker.ChunkDraft;
import com.docmind.ingest.chunker.ChunkResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** chunk 入库：父块（无向量，返回给模型）逐条插入取回 id，子块（带向量，参与检索）批量插入 */
@Component
public class ChunkIndexer {

    private final JdbcTemplate jdbc;
    private final ChineseTokenizer chineseTokenizer;

    public ChunkIndexer(JdbcTemplate jdbc, ChineseTokenizer chineseTokenizer) {
        this.jdbc = jdbc;
        this.chineseTokenizer = chineseTokenizer;
    }

    public void deleteByDocument(long documentId) {
        jdbc.update("DELETE FROM chunk WHERE document_id = ?", documentId);
    }

    public void insertChunks(long documentId, ChunkResult result, float[][] childVectors, String strategy) {
        List<ChunkDraft> parents = result.parents();
        List<ChunkDraft> children = result.children();
        if (children.size() != childVectors.length) {
            throw new IllegalStateException("子块数与向量数不一致：" + children.size() + " vs " + childVectors.length);
        }

        // 父块：数量级为百，逐条插入取回自增 id（子块要挂 parent_id）
        long[] parentIds = new long[parents.size()];
        for (int i = 0; i < parents.size(); i++) {
            final ChunkDraft parent = parents.get(i);
            final int parentIndex = i;
            KeyHolder keys = new GeneratedKeyHolder();
            String sql = """
                    INSERT INTO chunk(document_id, parent_id, strategy, content, token_count, section_path, page_no, chunk_index)
                    VALUES (?, NULL, ?, ?, ?, ?, ?, ?)
                    """;
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
                ps.setLong(1, documentId);
                ps.setString(2, strategy);
                ps.setString(3, parent.content());
                ps.setInt(4, parent.tokenCount());
                ps.setString(5, parent.sectionPath());
                if (parent.pageNo() != null) ps.setInt(6, parent.pageNo());
                else ps.setNull(6, Types.INTEGER);
                ps.setInt(7, parentIndex);
                return ps;
            }, keys);
            parentIds[i] = keys.getKey() == null ? -1 : keys.getKey().longValue();
        }

        // 子块：批量，带向量、全文索引（jieba 分词 → to_tsvector）与 parent_id
        List<Object[]> args = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            ChunkDraft child = children.get(i);
            Long parentId = child.parentIndex() == null ? null
                    : (child.parentIndex() < parentIds.length ? parentIds[child.parentIndex()] : null);
            args.add(new Object[]{documentId, parentId, strategy, child.content(), child.tokenCount(),
                    child.sectionPath(), child.pageNo(), parents.size() + i,
                    toVectorLiteral(childVectors[i]),
                    chineseTokenizer.segment(child.content())});
        }
        jdbc.batchUpdate("""
                INSERT INTO chunk(document_id, parent_id, strategy, content, token_count, section_path, page_no, chunk_index, embedding, content_tsv)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, to_tsvector('simple', ?))
                """, args);
    }

    /** float[] → "[0.12,0.34,...]"，pgvector 接受该字面量（召回侧构造查询向量复用） */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 9).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
