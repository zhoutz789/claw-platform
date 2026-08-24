-- =====================================================================
-- Claw 平台 V11 迁移（v2.0 — 权限模板化 6 角色）
-- 依据：《全风险规避方案 v2.0》T-B1 修复 + PRD D46
-- 范围：
--   · 废弃旧角色（CONSUMER/PRODUCER/DISTRIBUTOR/MERCHANT/OWNER/DRIVER/STATION_OWNER/FLEET_ADMIN）
--   · 新增 6 个固定角色模板，含完整 grants JSON
--   · 旧 user_role_packages 映射到新角色
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 新增 6 个固定角色模板
--    grants JSON 定义每个角色的权限范围，替代自由组合
-- ---------------------------------------------------------------------

-- CUSTOMER: 消费者（换电/租车/充电/查看订单/充值/提现）
INSERT INTO claw.roles (code, name_i18n, grants, auto_grant, status)
VALUES ('CUSTOMER',
        'role.customer.name',
        '{"permissions":["SWAP_BATTERY","RENT_VEHICLE","CHARGE_BATTERY","VIEW_OWN_ORDERS","VIEW_OWN_BALANCE","DEPOSIT_PAYMENT","WALLET_RECHARGE","WALLET_WITHDRAW","VIEW_NEARBY_STATIONS","RATE_STATION"]}',
        TRUE,
        'ACTIVE')
ON CONFLICT (code) DO UPDATE SET
    name_i18n = EXCLUDED.name_i18n,
    grants = EXCLUDED.grants,
    auto_grant = EXCLUDED.auto_grant,
    status = EXCLUDED.status,
    updated_at = now();

-- OPERATOR: 站长（收发电池/充电/日账单/管理收益/光伏管理）
INSERT INTO claw.roles (code, name_i18n, grants, auto_grant, status)
VALUES ('OPERATOR',
        'role.operator.name',
        '{"permissions":["RECEIVE_BATTERY","DISPATCH_BATTERY","CHARGE_BATTERY","VIEW_STATION_BILL","MANAGE_STATION_BATTERY_SLOTS","VIEW_OPERATOR_REVENUE","MANAGE_PV_PANELS","VIEW_OPERATOR_ACCOUNT","OPERATOR_KYC_REQUIRED","CUSTODY_TRANSFER"]}',
        FALSE,
        'ACTIVE')
ON CONFLICT (code) DO UPDATE SET
    name_i18n = EXCLUDED.name_i18n,
    grants = EXCLUDED.grants,
    auto_grant = EXCLUDED.auto_grant,
    status = EXCLUDED.status,
    updated_at = now();

-- ASSET_OWNER: 资产所有人（购买资产/共享池/收益分成/残值回收）
INSERT INTO claw.roles (code, name_i18n, grants, auto_grant, status)
VALUES ('ASSET_OWNER',
        'role.asset_owner.name',
        '{"permissions":["PURCHASE_ASSET","LIST_IN_SHARED_POOL","SET_RENTAL_SHARE","VIEW_REVENUE_SHARE","REQUEST_RECOVERY","TRADE_IN","VIEW_ASSET_STATUS","VIEW_ASSET_SOH","VIEW_CUSTODY_CHAIN","SET_USAGE_FEE"]}',
        FALSE,
        'ACTIVE')
ON CONFLICT (code) DO UPDATE SET
    name_i18n = EXCLUDED.name_i18n,
    grants = EXCLUDED.grants,
    auto_grant = EXCLUDED.auto_grant,
    status = EXCLUDED.status,
    updated_at = now();

-- FINANCE_ADMIN: 财务管理员（对账/资金/分账/费率配置）
INSERT INTO claw.roles (code, name_i18n, grants, auto_grant, status)
VALUES ('FINANCE_ADMIN',
        'role.finance_admin.name',
        '{"permissions":["VIEW_RECONCILIATION","APPROVE_SETTLEMENT","VIEW_ALL_ACCOUNTS","CONFIGURE_FEE_RULES","VIEW_REVENUE_SPLIT","MANUAL_ADJUSTMENT","EXPORT_FINANCIAL_REPORTS","VIEW_OPERATOR_BONDS"]}',
        FALSE,
        'ACTIVE')
ON CONFLICT (code) DO UPDATE SET
    name_i18n = EXCLUDED.name_i18n,
    grants = EXCLUDED.grants,
    auto_grant = EXCLUDED.auto_grant,
    status = EXCLUDED.status,
    updated_at = now();

-- RISK_OFFICER: 风控官（风控监控/熔断/审计/争议仲裁）
INSERT INTO claw.roles (code, name_i18n, grants, auto_grant, status)
VALUES ('RISK_OFFICER',
        'role.risk_officer.name',
        '{"permissions":["VIEW_RISK_EVENTS","TRIGGER_CIRCUIT_BREAKER","VIEW_CUSTODY_AUDIT","RESOLVE_DISPUTES","VIEW_OPERATOR_RISK","MANUAL_FREEZE_ACCOUNT","SUSPEND_OPERATOR","VIEW_ANOMALY_REPORTS"]}',
        FALSE,
        'ACTIVE')
ON CONFLICT (code) DO UPDATE SET
    name_i18n = EXCLUDED.name_i18n,
    grants = EXCLUDED.grants,
    auto_grant = EXCLUDED.auto_grant,
    status = EXCLUDED.status,
    updated_at = now();

-- SUPER_ADMIN: 超级管理员（全部权限）
INSERT INTO claw.roles (code, name_i18n, grants, auto_grant, status)
VALUES ('SUPER_ADMIN',
        'role.super_admin.name',
        '{"permissions":["*"]}',
        FALSE,
        'ACTIVE')
ON CONFLICT (code) DO UPDATE SET
    name_i18n = EXCLUDED.name_i18n,
    grants = EXCLUDED.grants,
    auto_grant = EXCLUDED.auto_grant,
    status = EXCLUDED.status,
    updated_at = now();

-- ---------------------------------------------------------------------
-- 2. 废弃旧角色（标记 INACTIVE 而非删除，保留历史数据可查）
-- ---------------------------------------------------------------------
UPDATE claw.roles SET status = 'INACTIVE', updated_at = now()
WHERE code IN ('CONSUMER','PRODUCER','DISTRIBUTOR','MERCHANT','OWNER','DRIVER','STATION_OWNER','FLEET_ADMIN','PLATFORM_ADMIN')
  AND code NOT IN ('CUSTOMER','OPERATOR','ASSET_OWNER','FINANCE_ADMIN','RISK_OFFICER','SUPER_ADMIN');

-- ---------------------------------------------------------------------
-- 3. 旧角色 → 新角色映射（数据迁移）
--    CONSUMER     → CUSTOMER (auto_grant)
--    STATION_OWNER → OPERATOR
--    OWNER         → ASSET_OWNER
--    PLATFORM_ADMIN → SUPER_ADMIN
--    DRIVER         → CUSTOMER (驱动权限内含于CUSTOMER)
--    FLEET_ADMIN    → ASSET_OWNER (车队管理员 = 资产所有人)
--    PRODUCER       → INACTIVE (生产者概念已废弃)
--    DISTRIBUTOR    → INACTIVE
--    MERCHANT       → INACTIVE
-- ---------------------------------------------------------------------

-- 映射旧 user_roles 到新角色
INSERT INTO claw.user_roles (user_id, role_id, granted_by, tenant_id, created_at)
SELECT ur.user_id, r_new.id, ur.granted_by, ur.tenant_id, now()
FROM claw.user_roles ur
JOIN claw.roles r_old ON ur.role_id = r_old.id
JOIN claw.roles r_new ON
    (r_old.code = 'STATION_OWNER' AND r_new.code = 'OPERATOR')
    OR (r_old.code = 'OWNER' AND r_new.code = 'ASSET_OWNER')
    OR (r_old.code = 'PLATFORM_ADMIN' AND r_new.code = 'SUPER_ADMIN')
    OR (r_old.code = 'DRIVER' AND r_new.code = 'CUSTOMER')
    OR (r_old.code = 'FLEET_ADMIN' AND r_new.code = 'ASSET_OWNER')
WHERE r_old.status = 'INACTIVE'
ON CONFLICT (user_id, role_id) DO NOTHING;

-- 映射旧 user_role_packages 到新角色
-- 注意：user_role_packages 表/实体均无 tenant_id、created_at 列，INSERT 列清单须与之匹配
INSERT INTO claw.user_role_packages (user_id, role_id, source)
SELECT urp.user_id, r_new.id, urp.source
FROM claw.user_role_packages urp
JOIN claw.roles r_old ON urp.role_id = r_old.id
JOIN claw.roles r_new ON
    (r_old.code = 'STATION_OWNER' AND r_new.code = 'OPERATOR')
    OR (r_old.code = 'OWNER' AND r_new.code = 'ASSET_OWNER')
    OR (r_old.code = 'PLATFORM_ADMIN' AND r_new.code = 'SUPER_ADMIN')
    OR (r_old.code = 'DRIVER' AND r_new.code = 'CUSTOMER')
    OR (r_old.code = 'FLEET_ADMIN' AND r_new.code = 'ASSET_OWNER')
    OR (r_old.code = 'CONSUMER' AND r_new.code = 'CUSTOMER')
WHERE r_old.status = 'INACTIVE'
ON CONFLICT DO NOTHING;

-- 给所有已有用户自动授予 CUSTOMER 角色（如未持有）
-- 注意：source 列实为 VARCHAR（实体用 @Enumerated(EnumType.STRING) 存字符串），
-- 项目从未在迁移中 CREATE TYPE 任何 PG 枚举，故此处直接写字符串 'AUTO'，不可 cast 到不存在的枚举类型
INSERT INTO claw.user_role_packages (user_id, role_id, source)
SELECT u.id, r.id, 'AUTO'
FROM claw.users u
CROSS JOIN claw.roles r
WHERE r.code = 'CUSTOMER'
  AND NOT EXISTS (
      SELECT 1 FROM claw.user_role_packages urp
      WHERE urp.user_id = u.id AND urp.role_id = r.id
  )
ON CONFLICT DO NOTHING;
