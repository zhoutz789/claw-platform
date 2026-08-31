-- =====================================================================
-- Claw 平台 V65 增量（certificates 补软删除列）
--
-- 背景（真库冒烟暴露的潜伏缺陷）：
--   · 实体 Certificate（domain/order/Certificate.java）有 `deleted` 字段，
--     映射 claw.certificates；
--   · 但 V34 建表时该表<b>没有 deleted 列</b>（建表清单里唯一缺软删除列的主表）。
--   · local profile 用 H2 + ddl-auto=update，Hibernate 自动补列，问题被掩盖；
--     真库 profile 是 ddl-auto=none + Flyway，缺什么硬报什么。
--   · 实体上没有 @SQLRestriction/@Where，也不继承软删除基类，因此 Hibernate
--     生成的 SELECT/UPDATE 一律带上 deleted —— 任何一次查表都会 SQL 报错。
--     当前没有 GET 接口走到它，所以冒烟没暴露，属潜伏缺陷。
--
-- 修复口径（与全库软删除惯例一致）：
--   · 补一列 deleted BOOLEAN NOT NULL DEFAULT false；
--   · 其它表（customer_orders / assets / swap_orders …）都是这个形态：
--     NOT NULL + DEFAULT false，存量行由 DEFAULT 兜底，无需手写回填。
--
-- 幂等性：
--   · 用 information_schema 判断列是否存在，存在则整段跳过（DO 块内无副作用）。
--
-- 不触碰：
--   · claw.device_certificates（V56 设备合格证表，与本项无关）；
--   · claw.customer_orders.certificate_id 等外键引用，本列不参与任何约束。
-- =====================================================================

SET search_path = claw;

-- ---------------------------------------------------------------------
-- 1. 补软删除列（缺失时才加；已存在则跳过，保证二次执行无副作用）
--    DEFAULT false 会自动把存量行的值填成 false，NOT NULL 因此不会失败。
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'claw'
           AND table_name   = 'certificates'
           AND column_name  = 'deleted'
    ) THEN
        ALTER TABLE claw.certificates
            ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT false;
    END IF;
END $$;
