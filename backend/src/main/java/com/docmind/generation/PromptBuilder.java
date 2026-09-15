package com.docmind.generation;

import com.docmind.retrieve.recall.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 组装（03 号文档 §5.2）：
 * system 约束"仅依据资料回答 + 角标引用 + 无依据拒答"，上下文块带 [n] 编号与出处前缀。
 */
@Component
public class PromptBuilder {

    public static final String SYSTEM_PROMPT = """
            你是一个友好、专业的备考助教。学生上传了学习资料，你帮助他们理解和掌握这些内容。

            **日常交流**（打招呼、感谢、闲聊）：
            - 自然友好地回应，像个真人助教
            - 简单聊几句后，温和引导学生提问，如"你可以问我资料里的任何内容"

            **知识问题**（问概念、原理、定义、比较等）：
            - 先引用资料中的相关内容，用 [n] 标注出处
            - 在此基础上深入讲解：原理机制、应用场景、实际例子
            - 可以适当延伸到相关知识点，帮助学生建立体系
            - 如果资料中没有直接提到，也照常讲解，注明"资料中未直接提及，以下为补充"

            **追问和继续**：
            - 学生说"继续"或"没讲完" → 接着上一条继续
            - 学生问"那XX呢" → 结合上下文理解并回答
            - 学生表示不理解 → 换一种更简单的方式解释，举例子

            你的目标是让学生真正理解知识，不是一个死板的搜索引擎。
            回答使用中文。""";

    public static final String LEARN_SYSTEM_PROMPT = SYSTEM_PROMPT;

    public String buildUserPrompt(String question, List<RetrievedChunk> context) {
        return buildUserPrompt(question, context, null);
    }

    public String buildUserPrompt(String question, List<RetrievedChunk> context,
                                  List<com.docmind.api.dto.ChatRequest.HistoryItem> history) {
        StringBuilder sb = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            sb.append("之前的对话（供理解上下文，不需要重复回答）：\n");
            for (var h : history) {
                String role = "user".equals(h.role()) ? "学生" : "导师";
                sb.append(role).append("：")
                        .append(h.content(), 0, Math.min(h.content().length(), 200))
                        .append('\n');
            }
            sb.append('\n');
        }
        sb.append("参考资料：\n\n");
        int n = 1;
        for (RetrievedChunk chunk : context) {
            sb.append('[').append(n++).append("] ");
            sb.append("来源: ").append(chunk.fileName());
            if (chunk.sectionPath() != null && !chunk.sectionPath().isBlank()) {
                sb.append(" · ").append(chunk.sectionPath());
            }
            if (chunk.pageNo() != null) {
                sb.append(" · 第").append(chunk.pageNo()).append("页");
            }
            sb.append('\n').append(chunk.content()).append("\n\n");
        }
        sb.append("问题：").append(question);
        return sb.toString();
    }
}
