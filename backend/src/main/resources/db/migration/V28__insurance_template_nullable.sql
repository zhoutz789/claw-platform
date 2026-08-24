-- =====================================================================
-- V28：车辆保险单 template_id 改为可空
-- 问题：V15 将 vehicle_insurance.template_id 定义为 NOT NULL，但投保业务允许
--       不挂载预定义保险方案模板（后台手动创建 ad-hoc 保单），导致
--       createPolicy 传 templateId=null 时违反非空约束（500）。
-- 修复：DROP NOT NULL，保留对 insurance_policy_templates 的可空外键引用。
-- =====================================================================

ALTER TABLE claw.vehicle_insurance ALTER COLUMN template_id DROP NOT NULL;
