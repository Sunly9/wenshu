package com.docmind.service;

import com.docmind.common.exception.ApiException;
import com.docmind.generation.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 示例问题建议：按库的实际内容生成（每个库缓存，资料变化后指纹失效）。
 * LLM 从随机采样的内容块生成 4 个问题；失败时降级为章节标题提问。
 */
@Service
public class SuggestService {

    private static final Logger log = LoggerFactory.getLogger(SuggestService.class);

    private static final String SYSTEM = """
            你是一个问答系统的开场引导员。根据给定的资料片段，猜测用户最可能想问的 4 个问题。
            问题必须能从资料中找到答案、口语化、各不相同。输出合法 JSON：{"questions":["问题1","问题2","问题3","问题4"]}""";

    private final JdbcTemplate jdbc;
    private final KbService kbService;
    private final LlmClient llmClient;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public SuggestService(JdbcTemplate jdbc, KbService kbService, LlmClient llmClient) {
        this.jdbc = jdbc;
        this.kbService = kbService;
        this.llmClient = llmClient;
    }

    public List<String> suggest(long kbId, String visitorId) {
        kbService.requireAccessible(kbId, visitorId);

        List<Map<String, Object>> fp = jdbc.queryForList(
                "SELECT count(*) AS n, coalesce(max(c.id), 0) AS max_id FROM chunk c "
                        + "JOIN document d ON d.id = c.document_id WHERE d.kb_id = ? AND d.status = 'READY'", kbId);
        long fingerprint = ((Number) fp.get(0).get("n")).longValue() * 1_000_000_3L
                + ((Number) fp.get(0).get("max_id")).longValue();

        Cached cached = cache.get(kbId);
        if (cached != null && cached.fingerprint == fingerprint) {
            return cached.questions;
        }

        List<String> questions;
        try {
            questions = generateByLlm(kbId);
        } catch (Exception e) {
            log.warn("示例问题生成失败，降级为章节标题：{}", e.getMessage());
            questions = fromSections(kbId);
        }
        if (questions.isEmpty()) {
            questions = fromSections(kbId);
        }
        if (questions.isEmpty()) {
            questions = List.of("这份资料主要讲了什么？");
        }
        cache.put(kbId, new Cached(fingerprint, questions));
        return questions;
    }

    private List<String> generateByLlm(long kbId) throws Exception {
        List<Map<String, Object>> units = jdbc.queryForList(
                """
                        SELECT c.content FROM chunk c
                        JOIN document d ON d.id = c.document_id
                        WHERE d.kb_id = ? AND d.status = 'READY' AND c.parent_id IS NULL
                        ORDER BY random() LIMIT 8
                        """, kbId);
        if (units.size() < 2) {
            return List.of();
        }
        StringBuilder user = new StringBuilder("资料片段：\n\n");
        for (Map<String, Object> unit : units) {
            String content = String.valueOf(unit.get("content"));
            user.append("- ").append(content, 0, Math.min(content.length(), 600)).append("\n");
        }
        user.append("\n请给出 4 个用户可能想问的问题。");
        String json = llmClient.complete(SYSTEM, user.toString(), true, 0.5);
        List<String> questions = new ArrayList<>();
        for (var node : new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(json).path("questions")) {
            if (questions.size() >= 4) break;
            String q = node.asText().trim();
            if (!q.isEmpty()) questions.add(q);
        }
        return questions;
    }

    /** 降级：用库内章节标题生成（"「X」这一章讲了什么？"） */
    private List<String> fromSections(long kbId) {
        List<String> sections = jdbc.queryForList(
                """
                        SELECT DISTINCT split_part(c.section_path, ' > ', 1) FROM chunk c
                        JOIN document d ON d.id = c.document_id
                        WHERE d.kb_id = ? AND d.status = 'READY' AND c.section_path IS NOT NULL
                        ORDER BY 1 LIMIT 4
                        """, String.class, kbId);
        return sections.stream()
                .filter(s -> !s.isBlank())
                .map(s -> "「" + s.trim() + "」这一章讲了什么？")
                .limit(4)
                .toList();
    }

    private record Cached(long fingerprint, List<String> questions) {}
}
