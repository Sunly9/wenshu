## 多轮对话管理 + 智能助教 + 错题删除

### 1. 数据库（V6 迁移）
- `conversation` 表（id, kb_id, visitor_id, title, updated_at）
- `conversation_message` 表（conversation_id, role, content, citations JSONB, created_at）

### 2. 对话管理（后端）
- ConversationService：创建/列出/获取消息/删除/保存消息
- LLM 自动生成标题：第一轮问答完成后异步调 DeepSeek 总结为 10 字以内标题（不直接截取用户原话）
- ChatRequest 加 conversationId，ChatService 完成后自动保存 user + assistant 两条消息

### 3. 智能助教 Prompt
- 日常交流 → 自然回应 + 引导提问
- 知识问题 → 引用资料[n] + 深入讲解 + 延伸
- 追问/继续/不理解 → 接着讲 / 换方式解释
- 合并严格/学习为一个智能模式

### 4. 错题删除
- DELETE 接口 + 前端"已掌握 ✓"按钮

### 5. 前端
- 问模式左侧对话列表面板（可折叠）
- "新建对话"按钮
- 点击历史对话加载消息（从数据库读，刷新不丢）
- 删除对话按钮

### 6. 构建 + 测试 + 推送