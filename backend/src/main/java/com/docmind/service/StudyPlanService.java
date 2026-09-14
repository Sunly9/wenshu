package com.docmind.service;

import com.docmind.common.exception.ApiException;
import com.docmind.generation.LlmClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 学习计划：AI 读完全部资料后生成复习建议 */
@Service
public class StudyPlanService {

    private static final String SYSTEM = """
            你是一个备考教练。根据学生的资料章节列表，生成一份学习计划。输出 JSON：
            {"overview":"一句话总结资料覆盖的内容","chapters":[{"title":"章节名","priority":"高/中/低","focus":"建议重点关注什么","estimated_minutes":30}],"tips":["学习建议1","学习建议2"]}
            按重要性排序章节，标注每章预计学习时间。""";

    private final JdbcTemplate jdbc;
    private final KbService kbService;
    private final LlmClient llmClient;

    public StudyPlanService(JdbcTemplate jdbc, KbService kbService, LlmClient llmClient) {
        this.jdbc = jdbc;
        this.kbService = kbService;
        this.llmClient = llmClient;
    }

    public String generate(long kbId, String visitorId) {
        kbService.requireAccessible(kbId, visitorId);

        List<Map<String, Object>> chapters = jdbc.queryForList("""
                SELECT split_part(c.section_path, ' > ', 1) AS chapter,
                       count(*) AS chunks,
                       min(c.page_no) AS start_page
                FROM chunk c
                JOIN document d ON d.id = c.document_id
                WHERE d.kb_id = ? AND d.status = 'READY' AND c.section_path IS NOT NULL
                GROUP BY 1 ORDER BY min(c.chunk_index)
                """, kbId);

        if (chapters.isEmpty()) {
            throw new ApiException("资料库中还没有可分析的内容，请先上传文档");
        }

        StringBuilder sb = new StringBuilder("学生的资料包含以下章节：\n\n");
        for (Map<String, Object> ch : chapters) {
            sb.append("- ").append(ch.get("chapter"))
                    .append("（").append(((Number) ch.get("chunks")).intValue()).append("个内容块")
                    .append("，从第").append(ch.get("start_page")).append("页开始）\n");
        }
        sb.append("\n请生成学习计划。");

        return llmClient.complete(SYSTEM, sb.toString(), true, 0.3);
    }
}
