package com.docmind.quiz;

import com.docmind.common.exception.ApiException;
import com.docmind.service.KbService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 练习记录：交卷自动存档 + 错题本查询 */
@Service
public class QuizAttemptService {

    private final JdbcTemplate jdbc;
    private final KbService kbService;
    private final ObjectMapper mapper = new ObjectMapper();

    public QuizAttemptService(JdbcTemplate jdbc, KbService kbService) {
        this.jdbc = jdbc;
        this.kbService = kbService;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> save(long kbId, String visitorId,
                                    Map<String, Object> body) {
        kbService.requireAccessible(kbId, visitorId);
        try {
            List<Map<String, Object>> grades = (List<Map<String, Object>>) (List<?>) body.get("grades");
            int total = 0;
            for (Map<String, Object> g : grades) {
                total += ((Number) g.get("score")).intValue();
            }
            total = total / Math.max(grades.size(), 1);
            jdbc.update("""
                            INSERT INTO quiz_attempt(kb_id, visitor_id, questions, answers, grades, total_score)
                            VALUES (?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                            """,
                    kbId, visitorId,
                    mapper.writeValueAsString(body.get("questions")),
                    mapper.writeValueAsString(body.get("answers")),
                    mapper.writeValueAsString(grades),
                    total);
            return Map.of("saved", true, "totalScore", total);
        } catch (Exception e) {
            throw new ApiException("保存练习记录失败：" + e.getMessage());
        }
    }

    /** 历史练习列表（简要） */
    public List<Map<String, Object>> list(long kbId, String visitorId, int limit) {
        kbService.requireAccessible(kbId, visitorId);
        return jdbc.queryForList("""
                SELECT id, total_score, created_at::text AS created_at,
                       jsonb_array_length(questions) AS question_count,
                       (SELECT count(*) FROM jsonb_array_elements(grades) g
                        WHERE (g->>'score')::int < 60) AS wrong_count
                FROM quiz_attempt
                WHERE kb_id = ? AND visitor_id = ?
                ORDER BY id DESC LIMIT ?
                """, kbId, visitorId, limit);
    }

    /** 错题本：跨所有练习收集得分 < 60 的题（直接在 SQL 里展开 JSONB 字段） */
    public List<Map<String, Object>> wrongQuestions(long kbId, String visitorId) {
        kbService.requireAccessible(kbId, visitorId);
        return jdbc.queryForList("""
                SELECT a.id AS attempt_id,
                       a.created_at::text AS created_at,
                       q.value->>'type' AS type,
                       q.value->>'stem' AS stem,
                       q.value->>'options' AS options,
                       q.value->>'answer' AS answer,
                       q.value->>'explanation' AS explanation,
                       g.value->>'score' AS score,
                       g.value->>'comment' AS comment,
                       g.value->'missedSentences' AS missed_sentences,
                       ans.value::text AS user_answer
                FROM quiz_attempt a,
                     jsonb_array_elements(a.questions) WITH ORDINALITY AS q(value, qidx),
                     jsonb_array_elements(a.grades) WITH ORDINALITY AS g(value, gidx),
                     jsonb_array_elements(a.answers) WITH ORDINALITY AS ans(value, aidx)
                WHERE a.kb_id = ? AND a.visitor_id = ?
                  AND qidx = gidx AND gidx = aidx
                  AND (g.value->>'score')::int < 60
                ORDER BY a.id DESC
                LIMIT 50
                """, kbId, visitorId);
    }

    /** 单次练习详情 */
    public Map<String, Object> detail(long kbId, long attemptId, String visitorId) {
        kbService.requireAccessible(kbId, visitorId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM quiz_attempt WHERE id = ? AND kb_id = ? AND visitor_id = ?",
                attemptId, kbId, visitorId);
        if (rows.isEmpty()) {
            throw new ApiException(404, "练习记录不存在");
        }
        return rows.get(0);
    }
}
