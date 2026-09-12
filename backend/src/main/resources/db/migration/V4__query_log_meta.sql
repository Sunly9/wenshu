-- v1.6（D12）：query_log 增加检索链路元数据（各阶段耗时 + 漏斗计数），检索调试台数据源
ALTER TABLE query_log ADD COLUMN retrieval_meta JSONB;
