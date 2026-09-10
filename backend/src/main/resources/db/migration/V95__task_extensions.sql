SET search_path = claw;

-- ============================================================
-- P2 Task Hall 扩展表：出行（HAIL_RIDE/TAXI）与广告（AD）1:1 扩展
-- 复用 V93 任务主表 tasks 的状态机；结算仍走 TASK_SETTLEMENT 复式记账。
-- ============================================================

-- 出行任务 1:1 扩展（仅 task_type IN ('HAIL_RIDE','TAXI')）
CREATE TABLE task_ride (
    task_id BIGINT PRIMARY KEY REFERENCES tasks(id),
    origin_addr VARCHAR(255),
    dest_addr VARCHAR(255),
    ride_type VARCHAR(10),                                   -- HAIL|TAXI
    est_distance_km NUMERIC(10,2),
    est_duration_min INT,
    fare_model VARCHAR(20)                                   -- PER_KM|PER_TIME|FLAT
);

-- 广告任务 1:1 扩展（仅 task_type='AD'）
CREATE TABLE task_ad (
    task_id BIGINT PRIMARY KEY REFERENCES tasks(id),
    advertiser VARCHAR(200),
    media_url VARCHAR(512),
    display_duration VARCHAR(20),
    screen_type VARCHAR(20)                                  -- BODY|SCREEN
);
