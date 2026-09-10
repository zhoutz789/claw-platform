SET search_path = claw;

-- 资产能力标签列（CSV：LOGISTICS,RIDE_HAIL,TAXI,AD_DISPLAY,DRONE_OP,SWAP）。
-- 接单时校验 asset.capabilities 是否包含 task.capability_required。
ALTER TABLE claw.assets ADD COLUMN IF NOT EXISTS capabilities VARCHAR(255) DEFAULT '';

-- 既有车辆/电动车资产默认具备全部地面出行 + 广告能力。
UPDATE claw.assets
SET capabilities = 'LOGISTICS,RIDE_HAIL,TAXI,AD_DISPLAY'
WHERE asset_type IN ('VEHICLE', 'EV')
  AND (capabilities IS NULL OR capabilities = '');
