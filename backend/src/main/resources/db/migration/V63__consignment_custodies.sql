-- =====================================================================
-- Claw 平台 V63 增量（补建寄售占有权表 · 真库阻断缺陷修复）
-- 背景：
--   · 实体 domain/consignment/ConsignmentCustody（claw.consignment_custodies）
--     是全库唯一被业务代码实际使用的「寄售占有权」真源
--     ——「服务站仅寄售占有、货权在厂家」这条核心规则就落在这张表上。
--   · 但 V1~V62 全量迁移中从未创建过该表（grep 零命中）。
--   · 本地 profile（H2 + ddl-auto: update）由 Hibernate 自动建表把洞掩盖；
--     真库 profile（ddl-auto: none + Flyway）缺什么硬报什么，于是「铺货入站」
--     报 ERROR: relation "claw.consignment_custodies" does not exist。
-- 受影响链路：
--   · InventoryService.shipToStation / shipToStationBatch（厂家发货到服务站，入寄售库）
--   · TransferService.receive（站间调拨收货，开新占有权）
--   · 连带额度校验放行分支 C1（入站）/ C2（调拨入站）
-- 范围：
--   · 建表 consignment_custodies，字段严格对齐 ConsignmentCustody 实体
--     （字段名 / 类型 / 可空性 / 枚举存储方式）
--   · 建必要索引与外键（外键目标表 devices / manufacturers / stations /
--     transfer_orders 均已由 V8 / V23 / V5 / V49 创建）
-- 说明：
--   · 不改动、不回灌既有的 claw.custody_records（V48 建的旧占有权表，按 asset 粒度），
--     本表按 device 粒度，两者不互相引用。
--   · 全量幂等（CREATE ... IF NOT EXISTS / 外键随建表语句内联）。
-- =====================================================================

SET search_path = claw;

-- ---------------------------------------------------------------------
-- 1. 寄售占有权（每「设备 × 一段占有期」一行；与 inventory 当前行 1:1）
--    字段逐条对齐 domain/consignment/ConsignmentCustody：
--      id                Long   @GeneratedValue(IDENTITY)        -> BIGSERIAL
--      deviceId          Long   @Column(device_id, nullable=false) -> BIGINT NOT NULL
--      manufacturerId    Long   @Column(manufacturer_id, nullable=false) -> BIGINT NOT NULL（货权方，恒为厂家）
--      holderStationId   Long   @Column(holder_station_id)       -> BIGINT NULL（寄售持有站）
--      status            CustodyStatus  @Enumerated(STRING)      -> VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
--      liabilityHolder   String @Column(liability_holder, 20)    -> VARCHAR(20) NULL
--      transferredAt     Instant                                 -> TIMESTAMPTZ NULL
--      transferOrderId   Long                                    -> BIGINT NULL
--      endedAt           Instant                                 -> TIMESTAMPTZ NULL
--      endedReason       String @Column(ended_reason, 40)        -> VARCHAR(40) NULL
--      createdAt         Instant @Column(nullable=false)         -> TIMESTAMPTZ NOT NULL DEFAULT now()
--      updatedAt         Instant @Column(nullable=false)         -> TIMESTAMPTZ NOT NULL DEFAULT now()
--    枚举取值（common.enums.CustodyStatus）：ACTIVE / TRANSFERRED_OUT / RETURNED / RELEASED
--    liability_holder 取值：SOURCE_STATION / MANUFACTURER / LOGISTICS
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS consignment_custodies (
    id                BIGSERIAL PRIMARY KEY,
    device_id         BIGINT       NOT NULL REFERENCES devices (id),          -- 占有主体设备（业务粒度）
    manufacturer_id   BIGINT       NOT NULL REFERENCES manufacturers (id),    -- 货权方（始终厂家）
    holder_station_id BIGINT       REFERENCES stations (id),                  -- 寄售持有站（NULL = 不在站）
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',                 -- CustodyStatus：ACTIVE/TRANSFERRED_OUT/RETURNED/RELEASED
    liability_holder  VARCHAR(20),                                            -- 在途责任方：SOURCE_STATION/MANUFACTURER/LOGISTICS
    transferred_at    TIMESTAMPTZ,                                            -- 扫码交接时点（Q2 占有权转移点）
    transfer_order_id BIGINT       REFERENCES transfer_orders (id),           -- 关联调拨单
    ended_at          TIMESTAMPTZ,                                            -- 占有权结束时点（NULL = 仍有效）
    ended_reason      VARCHAR(40),                                            -- 结束原因：TRANSFERRED/RECOVERED/PICKED_UP ...
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 2. 索引
--    device_id 不做「全表唯一」：占有权流转会为同一设备留下历史行
--    （TransferService.receive 关旧行 ended_at + 开新行），全局唯一会让
--    调拨收货插不进去。改为「未结束的行唯一」的部分唯一索引，
--    既守住「一台设备同时只有一个有效占有权」这条核心不变式，
--    又保留占有权流转的历史留痕。
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS uq_cc_device_active
    ON consignment_custodies (device_id) WHERE ended_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_cc_device        ON consignment_custodies (device_id);
CREATE INDEX IF NOT EXISTS idx_cc_manufacturer  ON consignment_custodies (manufacturer_id);
CREATE INDEX IF NOT EXISTS idx_cc_station       ON consignment_custodies (holder_station_id, status);
CREATE INDEX IF NOT EXISTS idx_cc_status        ON consignment_custodies (status);
CREATE INDEX IF NOT EXISTS idx_cc_transfer_order ON consignment_custodies (transfer_order_id);
