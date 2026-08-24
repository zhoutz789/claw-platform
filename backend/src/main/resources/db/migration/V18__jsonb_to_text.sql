-- V18：将若干以"JSON 文本"语义存储、但被实体以 String 映射的 JSONB 列改为 TEXT。
-- 原因：FeeRule.shareJson / Role.grants / Role.grantRule / CustodyDispute.evidenceUrls
-- 在 Java 实体中是普通 String，Hibernate 会以 varchar 绑定，无法写入 JSONB 列。
-- 业务层把这些都当作自由 JSON 文本（前端以 textarea 编辑），TEXT 语义完全等价。
ALTER TABLE claw.fee_rules        ALTER COLUMN share_json    TYPE text USING share_json::text;
ALTER TABLE claw.roles           ALTER COLUMN grants        TYPE text USING grants::text;
ALTER TABLE claw.roles           ALTER COLUMN grant_rule    TYPE text USING grant_rule::text;
ALTER TABLE claw.custody_disputes ALTER COLUMN evidence_urls TYPE text USING evidence_urls::text;
