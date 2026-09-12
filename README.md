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

## 状态

开发中（三周排期，见设计文档第九节）。
