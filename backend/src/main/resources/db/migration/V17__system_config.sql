-- =====================================================================
-- Claw 平台 V17 增量表（v2.0 — 系统配置键值存储）
-- 依据：后台「系统配置」模块的 admin CRUD 需求
-- 范围：
--   · system_config: 平台级参数键值存储（押金/费率开关/风控阈值等）
-- 通用规范继承 V1：schema claw、tenant_id、deleted、时间戳
-- =====================================================================

CREATE TABLE claw.system_config (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    config_key   VARCHAR(128) NOT NULL UNIQUE,
    config_value TEXT,
    category     VARCHAR(64),
    description  TEXT,
    data_type    VARCHAR(16)  NOT NULL DEFAULT 'STRING',
    editable     BOOLEAN      NOT NULL DEFAULT TRUE,
    tenant_id    BIGINT       NOT NULL DEFAULT 1,
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now()
);

-- 初始平台参数（运营可维护）
INSERT INTO claw.system_config (config_key, config_value, category, description, data_type, editable) VALUES
    ('deposit.rate', '0.30', '押金', '电池押金占资产价值比例（固定30%）', 'NUMBER', true),
    ('platform.fee.rate', '0.10', '分账', '平台分账比例', 'NUMBER', true),
    ('station.fee.rate', '0.15', '分账', '站点分账比例', 'NUMBER', true),
    ('insurance.fee.rate', '0.05', '分账', '保险分账比例', 'NUMBER', true),
    ('risk.auto.circuit.break', 'true', '风控', '风险达到临界是否自动熔断', 'BOOLEAN', true),
    ('swap.service.fee', '0.50', '费率', '单次换电服务费（美元）', 'NUMBER', true);
