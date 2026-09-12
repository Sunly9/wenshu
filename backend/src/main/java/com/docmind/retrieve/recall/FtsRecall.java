package com.docmind.retrieve.recall;

import com.docmind.index.ChineseTokenizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 关键词（全文）召回：jieba 分词 → plainto_tsquery('simple')，与入库分词同口径（03 号文档 §5.1） */
@Component
public class FtsRecall {

    private final JdbcTemplate jdbc;
    private final ChineseTokenizer chineseTokenizer;

    public FtsRecall(JdbcTemplate jdbc, ChineseTokenizer chineseTokenizer) {
        this.jdbc = jdbc;
        this.chineseTokenizer = chineseTokenizer;
    }

    public List<RetrievedChunk> recall(long kbId, String question, int topK, String strategy) {
        // OR 语义召回（任一词命中即可，靠 ts_rank_cd 排序）；
        // 过滤纯符号 token：jieba 会切出 ( ) ？ 等，而 to_tsquery 把它们当语法字符直接报错
        String query = String.join(" | ", chineseTokenizer.segmentToList(question).stream()
                .distinct()
                .filter(t -> t.chars().anyMatch(c -> Character.isLetterOrDigit(c) || c >= 0x4E00))
                .toList());
        if (query.isBlank()) return List.of();
        return jdbc.query("""
                SELECT c.id, c.document_id, c.parent_id, d.file_name, c.section_path, c.page_no, c.content, c.token_count,
                       ts_rank_cd(c.content_tsv, q.query) AS score
                FROM chunk c
                JOIN document d ON d.id = c.document_id
                CROSS JOIN to_tsquery('simple', ?) AS q(query)
                WHERE d.kb_id = ? AND d.status = 'READY'
                  AND c.content_tsv IS NOT NULL AND c.content_tsv <> ''::tsvector
                  AND c.strategy = ?
                  AND c.content_tsv @@ q.query
                ORDER BY score DESC
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
                query, kbId, strategy, topK);
    }
}
