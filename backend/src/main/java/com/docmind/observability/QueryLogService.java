package com.docmind.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 查询日志：检索调试台的数据源（00 号文档 §5：retrieved 全量打分落库） */
@Service
public class QueryLogService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public QueryLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long save(long kbId, String visitorId, String question,
                     List<Map<String, Object>> retrieved, List<Long> chosenIds,
                     String answer, long latencyMs, Integer promptTokens, Integer completionTokens,
                     Map<String, Object> retrievalMeta) {
        try {
            String retrievedJson = mapper.writeValueAsString(retrieved);
            String metaJson = retrievalMeta == null ? null : mapper.writeValueAsString(retrievalMeta);
            String chosenArray = chosenIds.stream().map(String::valueOf)
                    .reduce((a, b) -> a + "," + b).map(s -> "{" + s + "}").orElse("{}");
            jdbc.update("""
                            INSERT INTO query_log(kb_id, visitor_id, question, retrieved, chosen_ids,
                                                  answer, latency_ms, prompt_tokens, completion_tokens, retrieval_meta)
                            VALUES (?, ?, ?, ?::jsonb, ?::bigint[], ?, ?, ?, ?, ?::jsonb)
                            """,
                    kbId, visitorId, question, retrievedJson, chosenArray, answer, (int) latencyMs,
                    promptTokens, completionTokens, metaJson);
            return jdbc.queryForObject("SELECT max(id) FROM query_log", Long.class);
        } catch (Exception e) {
            return -1;  // 日志失败不阻断问答主流程
        }
    }
}
