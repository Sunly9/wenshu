package com.docmind.service;

import com.docmind.common.exception.ApiException;
import com.docmind.common.exception.NotFoundException;
import com.docmind.generation.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 多轮对话管理：创建/列出/获取消息/删除/LLM 自动生成标题 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private static final String TITLE_PROMPT = "用10字以内简洁总结这个问题的主题，只输出标题文字，不要引号和句号：\n%s";

    private final JdbcTemplate jdbc;
    private final KbService kbService;
    private final LlmClient llmClient;

    public ConversationService(JdbcTemplate jdbc, KbService kbService, LlmClient llmClient) {
        this.jdbc = jdbc;
        this.kbService = kbService;
        this.llmClient = llmClient;
    }

    /** 创建新对话 */
    public Map<String, Object> create(long kbId, String visitorId) {
        kbService.requireAccessible(kbId, visitorId);
        jdbc.update("INSERT INTO conversation(kb_id, visitor_id, title) VALUES (?, ?, ?)",
                kbId, visitorId, "新对话");
        Long id = jdbc.queryForObject("SELECT max(id) FROM conversation", Long.class);
        return Map.of("id", id, "title", "新对话");
    }

    /** 对话列表（按更新时间倒序） */
    public List<Map<String, Object>> list(long kbId, String visitorId) {
        kbService.requireAccessible(kbId, visitorId);
        return jdbc.queryForList("""
                SELECT id, title, updated_at::text AS updated_at,
                       (SELECT count(*) FROM conversation_message WHERE conversation_id = c.id) AS msg_count
                FROM conversation c
                WHERE kb_id = ? AND visitor_id = ?
                ORDER BY updated_at DESC LIMIT 30
                """, kbId, visitorId);
    }

    /** 获取某对话的全部消息 */
    public List<Map<String, Object>> messages(long conversationId, String visitorId) {
        checkOwnership(conversationId, visitorId);
        return jdbc.queryForList("""
                SELECT role, content, citations::text AS citations
                FROM conversation_message
                WHERE conversation_id = ?
                ORDER BY created_at
                """, conversationId);
    }

    /** 删除对话 */
    public void delete(long conversationId, String visitorId) {
        checkOwnership(conversationId, visitorId);
        jdbc.update("DELETE FROM conversation WHERE id = ?", conversationId);
    }

    /** 保存消息（ChatService 在流式完成后调用） */
    public void saveMessage(long conversationId, String role, String content, String citationsJson) {
        jdbc.update("""
                INSERT INTO conversation_message(conversation_id, role, content, citations)
                VALUES (?, ?, ?, ?::jsonb)
                """, conversationId, role, content, citationsJson);
        jdbc.update("UPDATE conversation SET updated_at = now() WHERE id = ?", conversationId);
    }

    /** LLM 自动生成标题（异步，不阻塞用户） */
    @Async("ingestExecutor")
    public void generateTitle(long conversationId, String firstQuestion) {
        try {
            String title = llmClient.complete("你是一个标题生成器", 
                    String.format(TITLE_PROMPT, firstQuestion), false, 0.1);
            if (title != null && !title.isBlank() && title.length() <= 50) {
                jdbc.update("UPDATE conversation SET title = ? WHERE id = ? AND title = '新对话'",
                        title.trim(), conversationId);
                log.info("对话 {} 标题生成: {}", conversationId, title.trim());
            }
        } catch (Exception e) {
            log.warn("标题生成失败（保留默认标题）: {}", e.getMessage());
        }
    }

    /** 检查是否是首条消息（用于触发标题生成） */
    public boolean isFirstMessage(long conversationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM conversation_message WHERE conversation_id = ? AND role = 'user'",
                Integer.class, conversationId);
        return count != null && count <= 1;
    }

    private void checkOwnership(long conversationId, String visitorId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT kb_id FROM conversation WHERE id = ?", conversationId);
        if (rows.isEmpty()) {
            throw new NotFoundException("对话不存在");
        }
        kbService.requireAccessible(((Number) rows.get(0).get("kb_id")).longValue(), visitorId);
    }
}
