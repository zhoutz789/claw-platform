-- =====================================================================
-- Claw 平台 V56 增量（设备合格证独立建表）
--
-- 背景（P0 缺陷 · 会阻断生产入库写合格证）：
--   V48 设计说明写的是「合格证复用既有 claw.certificates（V34 建）」，但两边对不上，
--   R3「生产完成即生成合格证并入库」这条链路**写库必失败**：
--     · V34 claw.certificates 的实际列：
--         cert_type VARCHAR(20) NOT NULL / cert_no VARCHAR(40) NOT NULL UNIQUE /
--         product_link_id / asset_id / data_json TEXT / template_version /
--         issued_by VARCHAR(60) / issued_at / file_url / order_id / tenant_id / created_at / updated_at
--     · 而 domain/certificate/Certificate（设备合格证实体）需要：
--         device_id BIGINT NOT NULL UNIQUE / issued_by BIGINT / spec_json
--   缺口明细：
--     1) device_id 列：V34 表根本没有（只有 asset_id），插入报 column "device_id" does not exist；
--     2) issued_by 类型不符：V34 是 VARCHAR(60)，实体是 Long，写入报类型不匹配；
--     3) spec_json 列：V34 表没有（对应列叫 data_json TEXT）；
--     4) cert_type NOT NULL 且无默认值：设备合格证不写该列，插入直接违反非空约束。
--   且 claw.certificates 同时被 domain/order/Certificate（**在用的**订单合格证，
--   issuedBy('CLAW') 是字符串）映射，若去改列会直接打断订单合格证链路 —— 两边互斥。
--
-- 修复（已确认方案）：为设备合格证**独立建表** claw.device_certificates，
--   V34 的 claw.certificates **原样不动**（继续服务订单合格证）。
--   domain/certificate/Certificate 的 @Table 改指 device_certificates。
--
-- 写入路径（均已走新表）：
--   ProductionService.completeTask() -> CertificateService.issue() -> Certificate 实体
--   AdminProductionController /certificates/device/{id}[/reprint] -> CertificateService 读
--
-- 全量幂等：CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS。
--           （ADD COLUMN 之类无法 IF NOT EXISTS 的语句一条都没用。）
-- =====================================================================

SET search_path = claw;

-- ---------------------------------------------------------------------
-- 1) 设备合格证表（1 设备 1 证；device_id UNIQUE 为硬约束，Q8 不可补证）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_certificates (
    id              BIGSERIAL    PRIMARY KEY,
    device_id       BIGINT       NOT NULL UNIQUE REFERENCES devices (id),  -- 1:1 设备，重复出证直接冲突
    cert_no         VARCHAR(64)  NOT NULL UNIQUE,                          -- 出厂即生成，补打不换号（Q8）
    manufacturer_id BIGINT,                                                -- 出证厂家（冗余，便于按厂家检索）
    product_id      BIGINT,                                                -- 实例化来源产品（冗余，便于按型号检索）
    spec_json       JSONB,                                                 -- 规格快照（厂家/型号/资产类型/出证时间）
    issued_by       BIGINT,                                                -- 出证人（登录用户 id）
    issued_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  device_certificates              IS '设备合格证（V56）：与设备 1:1，生产完成即出证（Q8）。订单合格证另在 claw.certificates（V34）。';
COMMENT ON COLUMN device_certificates.device_id    IS '设备 id，UNIQUE 保证一机一证';
COMMENT ON COLUMN device_certificates.cert_no      IS '合格证编号，出厂即生成，补打不重新生成';
COMMENT ON COLUMN device_certificates.spec_json    IS '出证时的规格快照（manufacturerId/productId/productName/assetType/model/issuedAt）';

-- ---------------------------------------------------------------------
-- 2) 索引（全部 IF NOT EXISTS，重复执行 no-op）
--    主查询维度：按厂家 / 按型号 / 按出证人 / 按出证时间倒序
--    device_id、cert_no 已由 UNIQUE 约束自带唯一索引，不重复建。
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_devcert_manufacturer ON device_certificates (manufacturer_id);
CREATE INDEX IF NOT EXISTS idx_devcert_product      ON device_certificates (product_id);
CREATE INDEX IF NOT EXISTS idx_devcert_issued_by    ON device_certificates (issued_by);
CREATE INDEX IF NOT EXISTS idx_devcert_issued_at    ON device_certificates (issued_at DESC);
