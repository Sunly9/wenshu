-- v1.5（D9）：chunk 表记录分块策略标记——第 3 周消融实验需要多策略块并存、按策略过滤检索
ALTER TABLE chunk ADD COLUMN strategy VARCHAR(32) NOT NULL DEFAULT 'STRUCTURE_AWARE';
