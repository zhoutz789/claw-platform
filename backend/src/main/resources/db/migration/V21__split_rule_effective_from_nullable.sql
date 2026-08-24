-- V21: 放宽 revenue_split_rules.effective_from 非空约束
-- 前端分账规则表单将 effectiveFrom 设为可选，但原表列 NOT NULL 会导致留空创建时 500。
-- 改为可空，使演示阶段可自由新增分账规则。

ALTER TABLE claw.revenue_split_rules ALTER COLUMN effective_from DROP NOT NULL;
