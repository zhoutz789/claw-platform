-- V22：资产主表补充「厂家/商品/SKU/序列号」字段，闭合资产出生来源。
-- qr_code 字段已存在，此处仅补充来源链路列。幂等。
ALTER TABLE claw.assets
  ADD COLUMN IF NOT EXISTS manufacturer_id BIGINT,
  ADD COLUMN IF NOT EXISTS product_id      BIGINT,
  ADD COLUMN IF NOT EXISTS sku_id          BIGINT,
  ADD COLUMN IF NOT EXISTS serial_number   VARCHAR(64);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_indexes WHERE schemaname='claw' AND tablename='assets' AND indexname='uk_assets_serial'
  ) THEN
    CREATE UNIQUE INDEX uk_assets_serial ON claw.assets (serial_number) WHERE serial_number IS NOT NULL;
  END IF;
END $$;
