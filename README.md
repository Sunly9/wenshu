# 问书 DocMind

> 把备考资料变成能问、能查、能出题的私人助教——每个答案都标明出自哪份资料的第几页。

把教材、讲义、真题 PDF 丢进知识库，然后：

- **问**：流式回答 + 引用角标，点角标跳回原文对应页，资料里没有的明确拒答
- **查**：丢半句记不全的话，直接定位到原文段落
- **练**：圈定章节自动出题，答完对照原文判分，指出你漏掉的句子

## 技术栈

Spring Boot 3 (JDK 21) · PostgreSQL 17 + pgvector · Redis · Vue 3 + Vite + TS · ONNX Runtime (bge-small-zh / bge-reranker, CPU) · DeepSeek API · Docker Compose + GitHub Actions

不使用 LangChain / LlamaIndex——分片器与检索链自研，这是本项目的技术产出。

## 文档

- [产品说明 & 用户手册](docs/用户手册.md)
- [技术定稿 v1.0](docs/00-项目定稿-v1.0.md)（冻结规格：DDL / 参数 / API / 评测方案）
- [总体设计与开发计划](docs/03-总体设计与开发计划.md)

## 本地运行

前置：JDK 21、Docker、Node 18+。

```bash
# 1. 数据库（PostgreSQL17+pgvector / Redis，宿主机 6379 被占则自动映射 16379）
docker compose -f deploy/docker-compose.dev.yml up -d

# 2. 嵌入模型（约 95MB，国内走 hf-mirror 镜像）
mkdir -p backend/models/bge-small-zh-v1.5
curl -L -o backend/models/bge-small-zh-v1.5/model.onnx \
  https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model.onnx
curl -L -o backend/models/bge-small-zh-v1.5/tokenizer.json \
  https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/tokenizer.json

# 3. DeepSeek API Key（环境变量，不要写进任何文件）
#    Windows: setx DEEPSEEK_API_KEY sk-xxx （新开终端生效）

# 4. 后端（首次启动 Flyway 自动建表）
cd backend && mvn spring-boot:run

# 5. 前端
cd frontend && npm install && npm run dev
# 打开 http://localhost:5173
```

> 分词器为纯 Java 自研 WordPiece 实现（见 `backend/.../index/BertTokenizer.java`），
> 不依赖任何原生库——DJL 的 Rust 分词库在真实语料上会 panic 杀进程，排障记录见 D4 提交。

## 消融实验（真实教材语料，50 条人工标注评测集）

| 配置 | HitRate@5 | MRR | 正确拒答率 |
|---|---|---|---|
| ① 固定长度分块 + 纯向量召回 | 93.0% | 0.748 | — |
| ② 递归分隔符分块 + 纯向量 | 95.4% | 0.724 | — |
| ③ 结构感知分块 + 纯向量 | 90.7% | 0.572 | — |
| ④ ③ + 父子分块 | 95.4% | 0.729 | — |
| ⑤ ④ + 双路召回 + RRF | 97.7% | 0.765 | — |
| ⑥ ⑤ + Rerank 精排 | 97.7% | **0.942** | 42.9% |

**核心发现：Rerank 不提升召回率（HitRate 持平 97.7%），但把 MRR 从 0.765 拉到 0.942——命中的块几乎全部被顶到第 1 名。交叉编码器的价值在排序质量，而非找得更多。** 完整指标口径与逐题型数据见调试台"评测面板"。

## 状态

**M1 已达成（tag v0.1）**：上传教材 PDF → 浏览器提问 → 流式答案带页码引用 → 点角标看原文片段。

**M2 已达成（tag v0.2）**：检索做准 + 全程可解释。

- [x] 离线链路：解析(PDF/Word/Markdown) → 分片(结构感知/递归/固定长度三策略可切换) → CPU 向量化(ONNX) → 入库(pgvector+jieba 全文索引)
- [x] 父子分块（Small-to-Big）：320 token 子块检索命中，1024 token 父块返回模型；表格整块保留
- [x] 在线链路：双路召回(向量+关键词50) → RRF 融合(30) → bge-reranker 精排(10留8) → 拒答阈值(0.35)
- [x] **检索调试台**：召回漏斗 + 四列得分(向量/关键词/RRF/精排) + 耗时拆解 + 双策略并排对比，查询只跑检索不耗模型 token
- [x] **查模式**：丢半句记不全的话，直接定位原文段落（实测相关度 0.9998）
- [x] 拒答：资料里没有的明确说"未找到依据"（库外问题实测精排最高分 0.110 < 0.35 → 拒答）
- [ ] M3：出题判分、评测与消融实验、公网部署
