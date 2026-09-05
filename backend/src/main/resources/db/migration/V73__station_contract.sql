-- =====================================================================
-- Claw 平台 V73 增量（服务站合约 · 周老板 2026-09-06 拍板）
-- 依据：用户补充说明②「合约每三年一签，到期可以选择退出，保证金三月内退还」
-- 实体：com.claw.server.domain.contract.StationContract（schema=claw, table=station_contracts）
-- 服务：ContractService —— 激活时建 3 年期合约（幂等）/ 申请退出（+3 月退款窗口）/
--       保证金清算（全额/扣减后置 EXITED）/ 定时任务置 EXPIRED
-- 注意：不修改 V60/V71/V72（已应用迁移改内容会触发 Flyway 校验失败），本迁移在 V72 之后运行。
-- =====================================================================

SET search_path = claw;

CREATE TABLE IF NOT EXISTS station_contracts (
    id                BIGSERIAL PRIMARY KEY,
    contract_no       VARCHAR(64)  NOT NULL,
    station_id        BIGINT       NOT NULL,
    applicant_type    VARCHAR(20)  NOT NULL DEFAULT 'STATION',
    deposit_tier_id   BIGINT,
    deposit_amount    NUMERIC(16, 2) NOT NULL,
    credit_limit      NUMERIC(16, 2) NOT NULL,
    term_years        INTEGER      NOT NULL DEFAULT 3,
    signed_at         TIMESTAMP    NOT NULL,
    effective_from    TIMESTAMP    NOT NULL,
    effective_to      TIMESTAMP    NOT NULL,
    status            VARCHAR(24)  NOT NULL,
    exit_requested_at TIMESTAMP,
    refund_due_at     TIMESTAMP,
    refund_status     VARCHAR(24)  NOT NULL DEFAULT 'NONE',
    refund_amount     NUMERIC(16, 2),
    terminated_at     TIMESTAMP,
    remark            TEXT,
    tenant_id         BIGINT       NOT NULL DEFAULT 1,
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by        BIGINT,
    -- 同站仅允许一份进行中合约（ACTIVE / EXIT_REQUESTED 各一份），避免激活重试重复建约
    CONSTRAINT uq_station_contracts_station_status UNIQUE (station_id, status)
);

-- 检索索引：按站查合约 / 定时任务按状态+到期扫描
CREATE INDEX IF NOT EXISTS idx_station_contracts_station
    ON station_contracts (station_id);
CREATE INDEX IF NOT EXISTS idx_station_contracts_status_effective
    ON station_contracts (status, effective_to);
CREATE UNIQUE INDEX IF NOT EXISTS uq_station_contracts_contract_no
    ON station_contracts (contract_no);
