# RepoChat · 代码仓库智能问答平台 —— 简历项目方案

> ⚠️ **已归档**：本项目已改为文档知识库方向，现行方案见 `docs/01-项目方案-DocMind.md`。
> 本文件保留，作为后续"多数据源扩展"（在文档知识库之上增加代码仓库数据源）的参考。

> 一句话介绍：输入任意 GitHub 仓库地址，几分钟内生成一个可对话的技术文档助手，每个回答都带文件路径与行号引用。

---

## 一、为什么选这个题

| 判断维度 | 说明 |
|---|---|
| 真实需求 | 接手陌生开源项目时"看不懂、找不到代码在哪"是开发者的普遍痛点 |
| 技术深度 | AST 语法树分块、混合检索、重排、引用溯源、流式输出，每一层都能深挖 |
| 可量化 | 召回率、引用准确率、首字延迟，全部有数字可写 |
| 可演示 | 给个仓库地址就能跑，面试官可以当场试用 |
| 避免撞题 | 不做"文档问答"这种烂大街版本，用代码场景 + 引用溯源做差异化 |

**核心差异化**：市面上 RAG 教程普遍用"固定长度切分文本"，本项目针对代码特性改为**按函数/类结构分块并保留原始行号**，检索质量与可解释性显著提升。

---

## 二、技术选型

| 层次 | 选型 | 选它的理由 |
|---|---|---|
| 前端 | Vue 3 + Vite + TypeScript + Element Plus | 上手快，SSE 流式渲染资料多 |
| 后端 | Spring Boot 3.x（**Java 21 LTS**） | 与本地 Spring 技术栈一致，面试对口 |
| 数据库 | PostgreSQL 17 + pgvector | 一个库同时搞定向量检索 + 全文检索，不用额外引入 ES |
| 缓存/队列 | Redis | 缓存问答结果、异步索引任务队列、限流 |
| 向量化 | bge-small-zh-v1.5（ONNX，CPU 推理） | 约 100MB，纯 CPU 秒级完成，无需显卡 |
| 重排 | bge-reranker-base（ONNX，CPU）| 提升 Top-K 精度，可讲优化故事 |
| 大模型 | DeepSeek API（或通义千问） | 中文强、便宜、无需本地算力 |
| 部署 | Docker Compose | 一条命令起全栈，简历上直接写"一键部署" |

### 本机可行性结论（已实测）

- CPU：i7-13700H（14C/20T）——足以跑 CPU 版 Embedding 与 Rerank
- 内存：15.73 GB —— 够用，但**不能**在本机运行 7B 参数大模型
- 显卡：Intel Iris Xe 核显，无独立显卡 —— **放弃本地大模型方案**
- Docker：29.7.2 + Compose v5.3.1 已就绪
- 磁盘：C 盘仅剩 15.8 GB（⚠️），D 盘 220 GB，E 盘 175 GB

**结论：LLM 走云端 API，Embedding / Rerank 本地 CPU 推理。** 这是本机唯一舒服的路线，同时也是业界主流做法（算力与成本解耦）。

---

## 三、系统架构

```
Docker Compose 一键部署
├── nginx + Vue3        前端：对话界面、SSE 流式渲染、引用高亮
├── spring-boot         后端：仓库索引任务、混合检索、对话编排
├── redis               缓存问答结果、异步索引队列、接口限流
├── postgres+pgvector   向量索引 + 全文索引 + 业务数据
├── embedding-service   bge-small / bge-reranker，ONNX 跑 CPU
└── (外部) DeepSeek API  云端生成最终回答
```

### 数据索引链路

```
GitHub 仓库地址
  → git clone --depth 1 拉取
  → 语言识别 + tree-sitter 解析（按函数 / 类 / 方法切块）
  → 每个块保留 {文件路径, 起始行, 结束行, 符号名}
  → 生成块摘要 + 向量化
  → 写入 pgvector（向量）+ tsvector（全文）
```

### 问答检索链路

```
用户提问
  → 查询改写（多轮对话下结合历史）
  → 双路召回：向量检索 Top-20 ｜ 关键词检索 Top-20
  → RRF 融合排序
  → bge-reranker 重排 → 取 Top-5
  → 组装 Prompt（强制要求标注引用）
  → DeepSeek 流式生成 → SSE 推送给前端
  → 前端渲染引用角标，点击跳转对应文件行
```

---

## 四、数据库表设计（核心）

| 表 | 关键字段 |
|---|---|
| `repository` | id, url, name, default_branch, status, chunk_count, created_at |
| `code_chunk` | id, repo_id, file_path, start_line, end_line, symbol_name, language, content, token_count |
| `chunk_embedding` | chunk_id, embedding(vector 512), + content 的 tsvector 全文索引 |
| `conversation` | id, repo_id, title, created_at |
| `message` | id, conversation_id, role, content, latency_ms, token_usage |
| `message_citation` | message_id, chunk_id, rank, score（回答与引用的关联，用于评测） |
| `eval_question` | id, repo_id, question, expected_files（人工标注的评测集） |

---

## 五、API 接口设计

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/repo` | 提交仓库地址，创建索引任务，返回 taskId |
| GET | `/api/repo/{id}/status` | 轮询索引进度（已解析文件数 / 总文件数） |
| GET | `/api/repo` | 我的仓库列表 |
| POST | `/api/chat/stream` | SSE 流式问答，返回内容增量 + 引用列表 |
| GET | `/api/conversation/{id}` | 历史会话 |
| POST | `/api/eval/run` | 跑评测集，输出召回率 / 引用准确率报告 |
| GET | `/api/eval/{id}/report` | 查看评测报告（前端做成可视化面板） |

---

## 六、三周排期

### 第 1 周：打通 MVP 主链路
- [ ] Docker Compose 骨架跑起来（postgres + redis + 后端 + 前端）
- [ ] 仓库拉取 + 按文件/固定长度粗暴分块 + 向量入库
- [ ] 一个最简问答接口（无流式、无重排），能跑通即可
- [ ] 目标：**输入仓库地址能问出带引用的一条回答**

### 第 2 周：做深技术亮点
- [ ] 换成 tree-sitter 结构化分块（保留符号名与行号）
- [ ] 加关键词召回 + RRF 融合 + bge-reranker 重排
- [ ] 前端升级为 SSE 流式输出 + 引用角标跳转
- [ ] 索引任务异步化（Redis 队列 + 进度回传）
- [ ] 写压测脚本，留下改造前后的对比数据

### 第 3 周：评测、部署、包装
- [ ] 构造 30-50 条评测问题集，跑出召回率与引用准确率
- [ ] 前端加一个"评测报告"页面（这是最容易被记住的亮点）
- [ ] Docker Compose 一键部署上云，配置 Nginx 反代
- [ ] 补 README（架构图 + GIF 演示 + 压测表格）、写一篇技术博客
- [ ] 录一段 60 秒演示视频，贴到 README 顶部

---

## 七、可量化的简历话术（示例，数字按实测填）

> **RepoChat · 代码仓库智能问答平台** ｜ 个人项目 ｜ 全栈 ｜ [GitHub 链接] [在线演示]
> 技术栈：Vue3 + Spring Boot 3 / Java 21 + PostgreSQL(pgvector) + Redis + Docker Compose
>
> - 针对代码语义特性设计 AST 结构化分块方案，替代固定长度切分，Top-5 召回率由 62% 提升至 89%
> - 实现向量与关键词双路召回 + RRF 融合 + Cross-Encoder 重排，引用准确率达 91%，显著抑制模型幻觉
> - 基于 SSE 实现流式输出，首字延迟降至 1.2s；索引任务经 Redis 队列异步化，5k 行代码索引耗时 28s
> - 构建 50 条问题的自动化评测集与可视化报告面板，量化检索与生成质量
> - 使用 Docker Compose 完成全栈编排与云端部署，支持一键启动

---

## 八、面试官可能追问的问题（提前准备）

1. 代码为什么要用 AST 分块？和固定长度切分比，差在哪？（准备一个具体反例）
2. 向量检索和关键词检索各自擅长什么？RRF 为什么比加权求和更好？
3. Rerank 模型为什么比向量检索准？代价是什么？（延迟、算力）
4. 你怎么保证模型不瞎编引用？（Prompt 约束 + 引用后校验 + 评测集兜底）
5. 索引一个 5 万行的仓库要多久？瓶颈在哪？怎么优化？（并发、批量化、增量索引）
6. 多轮对话下怎么处理"它""这个函数"这类指代？（查询改写）
7. 向量维度 512 是够的还是要更大？召回数量 Top-20 怎么定的？（用评测集调出来的）
8. 为什么不用 Elasticsearch？pgvector 的局限在哪？

---

## 九、环境与部署注意事项

### 必须现在处理
1. **迁移 Docker 镜像存储位置到 D 盘**
   Docker Desktop → Settings → Resources → Advanced → Disk image location → 改为 `D:\DockerData`
   原因：C 盘仅剩 15.8 GB，整套镜像约 3-4 GB。
2. **限制 WSL2 内存占用**
   新建 `C:\Users\35115\.wslconfig`：
   ```ini
   [wsl2]
   memory=6GB
   processors=8
   swap=2GB
   ```
3. **Java 版本锁 21**
   项目 `<java.version>21</java.version>`，Docker 基础镜像用 `eclipse-temurin:21-jre`。

### 开发期与部署期的编排差异
- **开发期**：只在 Docker 里跑 postgres + redis + embedding-service，后端用 IDEA 直接跑、前端用 `npm run dev`，改代码即时生效。
- **部署期**：全部服务进 Compose，前端构建成静态资源由 Nginx 托管。

### 云端服务器建议
- 规格：2 核 4G 起（Spring Boot ~600MB + PG ~300MB + Redis ~100MB + Nginx ~50MB）
- 端口：国内服务器 80 端口需备案，先用 8080 等端口 + IP 直连演示
- 密钥：DeepSeek API Key 用环境变量注入，**绝对不要提交到仓库**

---

## 十、成本估算

| 项目 | 费用 |
|---|---|
| DeepSeek API（开发 + 演示期） | 约 ¥5-20 |
| 云服务器（2C4G 轻量） | 约 ¥60-100 / 月 |
| 域名（可选） | 约 ¥30-60 / 年 |
| 合计 | **百元以内** |

---

## 十一、下一步行动

1. 先做第 1 周的 MVP，**不要一上来就追求架构完美**。
2. 从第一天起就用 Git 提交，保持小步提交习惯（面试官会看 commit 历史）。
3. 每完成一个技术点，立刻把数据和结论记进 `docs/`，避免最后补材料时想不起细节。
