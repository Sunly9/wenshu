<div align="center">

# 问书 DocMind

**备考资料 AI 助教 — 每个答案都有出处**

[![Spring Boot](https://img.shields.io/badge/Spring_Boot_3-JDK_21-6DB33F?logo=springboot)](https://spring.io)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL_17-pgvector-336791?logo=postgresql)](https://www.postgresql.org)
[![Vue 3](https://img.shields.io/badge/Vue_3-TypeScript-4FC08D?logo=vuedotjs)](https://vuejs.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

把教材、讲义、真题丢进知识库，然后——**问它、查它、让它考你**。

</div>

---

## 为什么做这个

备考时你手上有几百页 PDF，但：

| 痛点 | 问书的解法 |
|---|---|
| 找一个知识点翻半天 PDF | 问一句，秒出答案 + 第几页 |
| ChatGPT 不知道你教材写了什么，会编 | 只根据你上传的资料回答，**资料里没有就明确拒答** |
| 想自测没题做 | 圈定章节自动出题，答完指出你漏了原文哪句 |

**核心主张：知识库答不准，不是模型不行，是分片和检索没做好。** 本项目用消融实验证明了这一点。

---

## 核心特性

### 🔍 自研 RAG 检索链（不用 LangChain）

```
问题 → 双路召回(向量50 + 关键词50) → RRF融合(30) → Rerank精排(8)
     → 拒答判断(阈值0.35) → Small-to-Big父块组装 → DeepSeek流式生成
```

- **双路召回**：语义向量 + jieba 分词全文检索，互补覆盖口语化提问和精确术语
- **RRF 融合**：无需调权的排名合并，两路都认可的块排最前
- **交叉编码器精排**：bge-reranker 逐对打分，实测 MRR 从 0.765 提升至 **0.942**（+23%）
- **拒答机制**：精排最高分 < 0.35 → 明确回答"文档中未找到依据"，不编造

### 📊 消融实验（6 配置 × 50 题）

| 配置 | HitRate@5 | MRR |
|---|---:|---:|
| ① 固定长度 + 纯向量 | 93.0% | 0.748 |
| ② 递归分隔符 + 纯向量 | 95.4% | 0.724 |
| ③ 结构感知 + 纯向量 | 90.7% | 0.572 |
| ④ ③ + 父子分块 | 95.4% | 0.729 |
| ⑤ ④ + 双路召回 + RRF | 97.7% | 0.765 |
| ⑥ ⑤ + Rerank 精排 | 97.7% | **0.942** |

> **核心发现**：Rerank 不提升召回率（HitRate 持平），但把 MRR 拉高 23%——**交叉编码器的价值在排序质量而非找得更多**。命中的块几乎全部被顶到第 1 名。

### ✅ 生成质量（LLM 裁判，43 题）

| 指标 | 结果 |
|---|---|
| 忠实度 | **100%** — 所有回答的结论全部有原文依据 |
| 引用准确率 | 71.9%（115/160 角标真正支撑结论） |
| 正确拒答率 | 42.9%（库外问题不编造） |

### 🛠 四种分片策略（可切换 + 可视化预览）

| 策略 | 原理 | 定位 |
|---|---|---|
| 固定长度 | token 数硬切 | 消融基线 |
| 递归分隔符 | `\n\n` → `\n` → `。` 层级断点 | 工程默认 |
| **结构感知** | 标题栈 + 表格整块保留 + 父子分块 | **主策略** |
| 语义分块 | 句向量相似度骤降处断句 | 对比项 |

上传前可在前端**实时预览切分效果**，切得不好换策略重切。

### 🖥 检索调试台

把 RAG 黑盒打开——每次检索的完整过程可视化：

- 召回漏斗（50+50 → 30 → 10 → 4）
- 每条候选的**四列得分**（向量/关键词/RRF/Rerank）并排对比
- 各阶段耗时拆解
- 双策略并排切换对比

### 📝 产品三件套

| 模式 | 功能 |
|---|---|
| **问** | 流式回答 + `[n]` 角标 → 点击直达原文出处段落 |
| **查** | 丢半句话/关键词 → 语义定位原文段落 |
| **练** | 圈定章节自动出题 → LLM 判分 → 指出漏掉的原文句子 → **错题本**自动收集 |

---

## 技术架构

```
┌─────────────────────────────────────────────────────┐
│                    用户浏览器                         │
│            Vue 3 + Element Plus + SSE               │
└──────────────────────┬──────────────────────────────┘
                       │ HTTP / SSE
┌──────────────────────▼──────────────────────────────┐
│              Spring Boot 3 (端口 8080)               │
│                                                     │
│  ┌─────────┐   ┌──────────┐   ┌─────────────────┐  │
│  │ 离线链路 │   │ 在线链路  │   │   产品功能       │  │
│  │ 解析器   │   │ 双路召回  │   │   问/查/练      │  │
│  │ 分片器   │→→ │ RRF融合   │→→ │   出题判分      │  │
│  │ 向量化   │   │ Rerank   │   │   错题本        │  │
│  └─────────┘   │ 拒答判断  │   │   口令共享      │  │
│                └──────────┘   └─────────────────┘  │
│                                                     │
│  ┌─────────────────┐  ┌──────────────────────────┐  │
│  │  ONNX Runtime   │  │       DeepSeek API       │  │
│  │  bge-small-zh   │  │    流式生成 (SSE)        │  │
│  │  bge-reranker   │  └──────────────────────────┘  │
│  │  (CPU, 无GPU)   │                                │
│  └─────────────────┘                                │
└───────┬─────────────────────┬───────────────────────┘
        │                     │
┌───────▼───────┐    ┌───────▼───────┐
│  PostgreSQL   │    │     Redis     │
│  17 + pgvector│    │   限流/缓存    │
│  + jieba全文  │    │               │
└───────────────┘    └───────────────┘
```

---

## 快速开始

### 前置条件

- JDK 21
- Docker
- Node.js 18+
- DeepSeek API Key（[免费注册](https://platform.deepseek.com)）

### 启动

```bash
# 1. 克隆项目
git clone https://github.com/Sunly9/wenshu.git
cd wenshu

# 2. 启动数据库（PostgreSQL + pgvector / Redis）
docker compose -f deploy/docker-compose.dev.yml up -d

# 3. 下载嵌入模型（约 95MB）
mkdir -p backend/models/bge-small-zh-v1.5
curl -L -o backend/models/bge-small-zh-v1.5/model.onnx \
  https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model.onnx
curl -L -o backend/models/bge-small-zh-v1.5/tokenizer.json \
  https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/tokenizer.json

# 4. 下载重排模型（约 279MB，可选——不装则降级为纯 RRF）
mkdir -p backend/models/bge-reranker-base
curl -L -o backend/models/bge-reranker-base/model.onnx \
  https://hf-mirror.com/Xenova/bge-reranker-base/resolve/main/onnx/model_quantized.onnx
curl -L -o backend/models/bge-reranker-base/tokenizer.json \
  https://hf-mirror.com/Xenova/bge-reranker-base/resolve/main/tokenizer.json

# 5. 配置 API Key
export DEEPSEEK_API_KEY=sk-your-key-here

# 6. 启动后端（首次启动自动建表）
cd backend && mvn spring-boot:run

# 7. 启动前端（另开终端）
cd frontend && npm install && npm run dev
```

打开 `http://localhost:5173`，建库 → 上传 PDF → 开始提问。

---

## 技术决策

| 决策 | 选择 | 为什么不选替代方案 |
|---|---|---|
| 向量库 | PostgreSQL + pgvector | 20 万块内向量+业务同库同事务最简。Milvus 是杀鸡用牛刀，Chroma 偏 demo |
| RAG 框架 | **自研** | LangChain 默认分片不适配中文/表格；封装深无法自定义；自研才能做消融实验 |
| 生成模型 | DeepSeek API | 中文效果好、价格低（本项目全部实验花费 < ¥5） |
| 向量/重排 | bge + ONNX CPU | 不需要 GPU；"能不用 GPU 跑起来"是加分项 |
| 分词器 | **纯 Java 自研** | DJL Rust 分词库在真实语料上 panic 杀 JVM（详见排障记录） |

---

## 项目结构

```
wenshu/
├── backend/                          # Spring Boot 3 后端
│   └── src/main/java/com/docmind/
│       ├── api/                      # Controller / DTO / 全局异常
│       ├── ingest/                   # 离线链路
│       │   ├── parser/               # PdfParser / MarkdownParser / WordParser
│       │   ├── chunker/              # 4 种分片策略 + 共享切分/打包组件
│       │   └── pipeline/             # 编排 + 状态机 + 断电恢复
│       ├── index/                    # 向量化 / 全文索引 / 自研分词器
│       ├── retrieve/                 # 在线链路
│       │   ├── recall/               # VectorRecall / FtsRecall
│       │   ├── fusion/               # RrfFusion
│       │   ├── rerank/               # OnnxRerankClient
│       │   └── assembler/            # Small-to-Big 父块组装
│       ├── generation/               # PromptBuilder / LlmClient / SSE
│       ├── quiz/                     # 出题 / 判分 / 错题本
│       ├── eval/                     # 评测Runner / 生成质量评测
│       └── observability/            # QueryLog / DebugService
├── frontend/                         # Vue 3 + Vite + TS + Element Plus
│   └── src/views/                    # 4 个页面
├── deploy/                           # Docker Compose / Nginx 配置
├── docs/                             # 设计文档（含冻结规格）
│   ├── 00-项目定稿-v1.0.md           # DDL / API / 参数 / 评测方案
│   └── 03-总体设计与开发计划.md       # 架构设计 + 21 天排期
└── .github/workflows/                # CI/CD（部署时启用）
```

---

## 实验

### 评测集

50 道人工标注问题（真实教材语料）：

| 题型 | 数量 | 考察 |
|---|---|---|
| 事实型 | 25 | 基本检索能力 |
| 多跳型 | 10 | 跨章节知识关联 |
| 表格型 | 8 | 表格是否被切碎 |
| 无答案 | 7 | 拒答（不编造） |

### 关键发现

1. **Rerank 的价值在排序而非召回** — MRR +23%，HitRate 持平
2. **相关性 ≠ 可答性** — 教材大篇幅讨论快速排序但不含其发明者，交叉编码器仍打高分（0.986）；简单阈值无法完美区分
3. **双路召回互补** — 关键词路把向量排第 11 的块捞到融合第 3

### 复现实验

```bash
# 打开调试台（http://localhost:5173/debug/1）
# → "评测面板" Tab → "一键跑消融实验"
# 或 API：
curl -X POST http://localhost:8080/api/eval/run \
  -H "Content-Type: application/json" \
  -H "X-Visitor-Id: your-id" \
  -d '{"kbId": 1}'
```

---

## 文档

| 文档 | 内容 |
|---|---|
| [用户手册](docs/用户手册.md) | 产品定位、功能说明、使用指南 |
| [技术定稿](docs/00-项目定稿-v1.0.md) | DDL、API 契约、参数冻结表、评测方案、变更记录 |
| [总体设计](docs/03-总体设计与开发计划.md) | 架构图、模块设计、21 天排期 |

---

## License

[MIT](LICENSE)

---

<div align="center">

**如果这个项目对你有帮助，点个 ⭐ Star**

</div>
