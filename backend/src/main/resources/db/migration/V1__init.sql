-- 问书（DocMind）V1 初始结构
-- 与 docs/00-项目定稿-v1.0.md §5 保持一致（含 v1.2 的 share_code / kb_visitor）

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_base (
  id              BIGSERIAL PRIMARY KEY,
  name            VARCHAR(64)  NOT NULL,
  description     VARCHAR(255),
  chunk_strategy  VARCHAR(32)  NOT NULL DEFAULT 'STRUCTURE_AWARE',
  embed_model     VARCHAR(64)  NOT NULL DEFAULT 'bge-small-zh-v1.5',
  embed_dim       INT          NOT NULL DEFAULT 512,
  share_code      CHAR(6)      NOT NULL UNIQUE,   -- 库口令：大写字母+数字，剔除 0O1I
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 库与访客（浏览器）的绑定关系，创建者与输口令加入者都在此表
CREATE TABLE kb_visitor (
  kb_id       BIGINT       NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
  visitor_id  VARCHAR(64)  NOT NULL,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (kb_id, visitor_id)
);

CREATE TABLE document (
  id          BIGSERIAL PRIMARY KEY,
  kb_id       BIGINT       NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
  file_name   VARCHAR(255) NOT NULL,
  file_type   VARCHAR(16)  NOT NULL,
  size_bytes  BIGINT       NOT NULL,
  page_count  INT,
  token_count INT,
  status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING/PARSING/INDEXING/READY/FAILED
  error_msg   TEXT,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_document_kb ON document(kb_id, created_at DESC);

CREATE TABLE chunk (
  id           BIGSERIAL PRIMARY KEY,
  document_id  BIGINT       NOT NULL REFERENCES document(id) ON DELETE CASCADE,
  parent_id    BIGINT       REFERENCES chunk(id) ON DELETE CASCADE,
  content      TEXT         NOT NULL,
  token_count  INT          NOT NULL,
  section_path VARCHAR(512),
  page_no      INT,
  chunk_index  INT          NOT NULL,
  embedding    vector(512),                       -- 父块为 NULL（不参与检索）
  content_tsv  tsvector,                          -- 父块为 NULL；jieba 应用层分词后写入
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_chunk_doc    ON chunk(document_id, chunk_index);
CREATE INDEX idx_chunk_parent ON chunk(parent_id);
CREATE INDEX idx_chunk_tsv    ON chunk USING GIN(content_tsv);

-- 注意：不预先建 HNSW 向量索引，5 万块内顺序扫描更快（00 号文档 §5 索引策略）

CREATE TABLE query_log (
  id            BIGSERIAL PRIMARY KEY,
  kb_id         BIGINT      NOT NULL,
  visitor_id    VARCHAR(64) NOT NULL,
  question      TEXT        NOT NULL,
  retrieved     JSONB,      -- [{chunkId, vectorScore, ftsScore, rrfScore, rerankScore}]
  chosen_ids    BIGINT[],
  answer        TEXT,
  latency_ms    INT,
  prompt_tokens INT,
  completion_tokens INT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_query_log_kb ON query_log(kb_id, created_at DESC);

CREATE TABLE eval_question (
  id             BIGSERIAL PRIMARY KEY,
  kb_id          BIGINT      NOT NULL,
  question       TEXT        NOT NULL,
  type           VARCHAR(16) NOT NULL,   -- FACT / MULTI_HOP / TABLE / NO_ANSWER
  gold_chunk_ids BIGINT[],
  gold_answer    TEXT        NOT NULL
);

CREATE TABLE eval_run (
  id                  BIGSERIAL PRIMARY KEY,
  kb_id               BIGINT      NOT NULL,
  config              JSONB       NOT NULL,  -- 本次启用了哪些策略
  hit_rate_at5        NUMERIC(5,4),
  mrr                 NUMERIC(5,4),
  citation_accuracy   NUMERIC(5,4),
  faithfulness        NUMERIC(5,4),
  detail              JSONB,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
