-- v1.2 口令方案的两处修正（详见 docs/00-项目定稿-v1.0.md 变更记录 v1.3）
-- 1) share_code CHAR(6) -> VARCHAR(6)：固定 6 位无需填充语义，且与 JPA 校验兼容
-- 2) knowledge_base.created_by：重置口令"仅创建者可调"需要创建者身份
ALTER TABLE knowledge_base ALTER COLUMN share_code TYPE VARCHAR(6);
ALTER TABLE knowledge_base ADD COLUMN created_by VARCHAR(64) NOT NULL;
