-- v2.3：多轮对话管理（历史对话保存/切换/标题）
CREATE TABLE conversation (
  id          BIGSERIAL PRIMARY KEY,
  kb_id       BIGINT       NOT NULL,
  visitor_id  VARCHAR(64)  NOT NULL,
  title       VARCHAR(100),
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversation_kb ON conversation(kb_id, visitor_id, updated_at DESC);

CREATE TABLE conversation_message (
  id               BIGSERIAL PRIMARY KEY,
  conversation_id  BIGINT       NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
  role             VARCHAR(10)  NOT NULL,
  content          TEXT         NOT NULL,
  citations        JSONB,
  created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_conv_msg ON conversation_message(conversation_id, created_at);
