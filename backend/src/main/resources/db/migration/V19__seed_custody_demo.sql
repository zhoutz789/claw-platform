-- V19：产权链 / 争议模块演示种子数据。
-- 这些实体没有后台"创建"入口（由业务流生成），预置少量数据便于体验追溯与仲裁。
INSERT INTO claw.custody_transfers (id, asset_id, asset_type, from_user_id, to_user_id, transfer_type, station_id, chain_hash, asset_soh, asset_soc, asset_cycle_count, transferred_at, tenant_id, deleted)
OVERRIDING SYSTEM VALUE VALUES
(1, 1, 'BATTERY', 1001, 1002, 'SHARED_POOL_ENTRY', 1, 'cf9a2b3e1d4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b', 95.00, 90.00, 12, now(), 1, false),
(2, 2, 'BATTERY', 1002, 1003, 'RENTAL_START',      2, 'df9a2b3e1d4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0c', 92.00, 85.00, 20, now(), 1, false);

INSERT INTO claw.custody_transfer_audit (id, transfer_id, asset_id, anomaly_type, risk_score, description, user_daily_transfer_count, asset_daily_transfer_count, detected_at, reviewed, tenant_id, deleted)
OVERRIDING SYSTEM VALUE VALUES
(1, 1, 1, 'OFF_HOURS_TRANSFER', 35, '凌晨高频转移，已标记为观察', 3, 1, now(), false, 1, false);

INSERT INTO claw.custody_disputes (id, transfer_id, asset_id, claimant_id, respondent_id, dispute_type, description, claim_amount, status, tenant_id, deleted)
OVERRIDING SYSTEM VALUE VALUES
(1, 1, 1, 1001, 1002, 'DAMAGE_CLAIM', '电池归还时外壳损伤，责任归属争议', 50.0000, 'PENDING', 1, false);
