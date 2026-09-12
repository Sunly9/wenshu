package com.docmind.eval;

import com.docmind.common.exception.ApiException;
import com.docmind.common.exception.NotFoundException;
import com.docmind.service.KbService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 评测集管理：D17 标注工具的存取（50 条目标：FACT 25 / MULTI_HOP 10 / TABLE 8 / NO_ANSWER 7） */
@Service
public class EvalQuestionService {

    static final Set<String> TYPES = Set.of("FACT", "MULTI_HOP", "TABLE", "NO_ANSWER");

    private final JdbcTemplate jdbc;
    private final KbService kbService;

    public EvalQuestionService(JdbcTemplate jdbc, KbService kbService) {
        this.jdbc = jdbc;
        this.kbService = kbService;
    }

    public Map<String, Object> save(long kbId, EvalQuestionRequest req, String visitorId) {
        kbService.requireAccessible(kbId, visitorId);
        if (req.question() == null || req.question().isBlank()) {
            throw new ApiException("问题不能为空");
        }
        String type = req.type() == null ? "" : req.type().toUpperCase();
        if (!TYPES.contains(type)) {
            throw new ApiException("题型必须是 FACT / MULTI_HOP / TABLE / NO_ANSWER");
        }
        if (!"NO_ANSWER".equals(type) && (req.goldChunkIds() == null || req.goldChunkIds().isEmpty())) {
            throw new ApiException("非无答案题型必须勾选至少一个标准答案块");
        }
        String idsArray = req.goldChunkIds() == null ? "{}" : "{" +
                req.goldChunkIds().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("") + "}";
        jdbc.update("""
                        INSERT INTO eval_question(kb_id, question, type, gold_chunk_ids, gold_answer)
                        VALUES (?, ?, ?, ?::bigint[], ?)
                        """,
                kbId, req.question().trim(), type, idsArray,
                req.goldAnswer() == null ? "" : req.goldAnswer().trim());
        return stats(kbId, visitorId);
    }

    public List<Map<String, Object>> list(long kbId, String visitorId) {
        kbService.requireAccessible(kbId, visitorId);
        return jdbc.queryForList(
                "SELECT id, type, question, gold_chunk_ids, gold_answer FROM eval_question WHERE kb_id = ? ORDER BY id DESC",
                kbId);
    }

    public void delete(long kbId, long id, String visitorId) {
        kbService.requireAccessible(kbId, visitorId);
        int deleted = jdbc.update("DELETE FROM eval_question WHERE id = ? AND kb_id = ?", id, kbId);
        if (deleted == 0) {
            throw new NotFoundException("评测题不存在");
        }
    }

    /** 标注进度统计 */
    public Map<String, Object> stats(long kbId, String visitorId) {
        kbService.requireAccessible(kbId, visitorId);
        Map<String, Object> stats = new HashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT type, count(*) AS n FROM eval_question WHERE kb_id = ? GROUP BY type", kbId);
        int total = 0;
        for (String t : TYPES) {
            stats.put(t, 0);
        }
        for (Map<String, Object> row : rows) {
            int n = ((Number) row.get("n")).intValue();
            stats.put(String.valueOf(row.get("type")), n);
            total += n;
        }
        stats.put("total", total);
        return stats;
    }
}
