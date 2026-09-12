package com.docmind.observability;

import com.docmind.api.dto.DebugResponse;
import com.docmind.common.exception.NotFoundException;
import com.docmind.retrieve.RetrievalService;
import com.docmind.retrieve.assembler.ContextAssembler;
import com.docmind.retrieve.recall.RetrievedChunk;
import com.docmind.service.KbService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 检索调试台（00 号文档 §7）：即时执行检索（不进 LLM）或读取历史 query_log */
@Service
public class DebugService {

    private final JdbcTemplate jdbc;
    private final KbService kbService;
    private final RetrievalService retrievalService;
    private final ContextAssembler assembler;
    private final QueryLogService queryLogService;
    private final ObjectMapper mapper = new ObjectMapper();

    public DebugService(JdbcTemplate jdbc, KbService kbService, RetrievalService retrievalService,
                        ContextAssembler assembler, QueryLogService queryLogService) {
        this.jdbc = jdbc;
        this.kbService = kbService;
        this.retrievalService = retrievalService;
        this.assembler = assembler;
        this.queryLogService = queryLogService;
    }

    /** 即时检索：跑完整检索链但不生成，结果落 query_log 供回看 */
    public DebugResponse debugQuery(long kbId, String question, String visitorId) {
        kbService.requireAccessible(kbId, visitorId);
        long start = System.currentTimeMillis();
        RetrievalService.RetrievalResult retrieval = retrievalService.recall(kbId, question);
        List<RetrievedChunk> context = assembler.assembleContext(retrieval.reranked());
        long latency = System.currentTimeMillis() - start;
        long queryId = queryLogService.save(kbId, visitorId, question,
                retrievalService.candidatesDetail(retrieval),
                context.stream().map(RetrievedChunk::chunkId).toList(),
                null, latency, null, null, retrievalService.meta(retrieval));
        return byQueryId(queryId, visitorId);
    }

    /** 读取历史查询的完整检索画像 */
    public DebugResponse byQueryId(long queryId, String visitorId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, kb_id, question, retrieved, chosen_ids, answer, latency_ms, retrieval_meta, created_at FROM query_log WHERE id = ?",
                queryId);
        if (rows.isEmpty()) {
            throw new NotFoundException("查询记录不存在");
        }
        Map<String, Object> row = rows.get(0);
        long kbId = ((Number) row.get("kb_id")).longValue();
        kbService.requireAccessible(kbId, visitorId);  // 防跨库偷看

        return new DebugResponse(
                queryId,
                kbId,
                (String) row.get("question"),
                (String) row.get("answer"),
                ((Number) row.get("latency_ms")).longValue(),
                parseJson(row.get("retrieved")),
                parseIds(row.get("chosen_ids")),
                parseJson(row.get("retrieval_meta")),
                String.valueOf(row.get("created_at")));
    }

    /** 最近查询（调试台首页列表） */
    public List<Map<String, Object>> recent(long kbId, String visitorId, int limit) {
        kbService.requireAccessible(kbId, visitorId);
        return jdbc.queryForList(
                "SELECT id, question, latency_ms, (answer IS NULL) AS debug_only, created_at FROM query_log WHERE kb_id = ? ORDER BY id DESC LIMIT ?",
                kbId, limit);
    }

    @SuppressWarnings("unchecked")
    private Object parseJson(Object value) {
        if (value == null) return null;
        try {
            return mapper.readValue(value.toString(), Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Long> parseIds(Object value) {
        if (value == null) return List.of();
        String s = value.toString();  // PG 数组字面量 {1,2}
        s = s.replaceAll("[{}\\s]", "");
        if (s.isBlank()) return List.of();
        return java.util.Arrays.stream(s.split(",")).map(Long::valueOf).toList();
    }
}
