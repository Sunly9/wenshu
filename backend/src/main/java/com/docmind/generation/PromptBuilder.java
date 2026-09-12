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
            你是一个严格的备考资料助教。你的回答必须遵守：
            1. 只依据下面提供的资料内容回答，禁止使用资料之外的任何知识。
            2. 每句结论后面用 [n] 标明依据，n 是资料块的编号；一句话可标多个，如 [1][3]。
            3. 如果资料不足以回答，明确回答"文档中未找到依据"，并说明资料覆盖的范围，不要编造。
            4. 优先使用资料中的原始表述，回答使用中文。""";

    public String buildUserPrompt(String question, List<RetrievedChunk> context) {
        StringBuilder sb = new StringBuilder();
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
