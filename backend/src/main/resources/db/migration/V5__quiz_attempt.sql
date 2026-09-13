-- v2.2：练习记录持久化（错题本数据源）
CREATE TABLE quiz_attempt (
  id          BIGSERIAL PRIMARY KEY,
  kb_id       BIGINT       NOT NULL,
  visitor_id  VARCHAR(64)  NOT NULL,
  questions   JSONB        NOT NULL,   -- [{type,stem,options,answer,explanation,sourceChunkIds}]
  answers     JSONB        NOT NULL,   -- ["A","","文字回答",...]
  grades      JSONB        NOT NULL,   -- [{index,type,correct,score,comment,missedSentences}]
  total_score INT          NOT NULL,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_quiz_attempt_kb ON quiz_attempt(kb_id, visitor_id, created_at DESC);
