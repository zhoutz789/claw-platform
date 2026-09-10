SET search_path = claw;

-- ============================================================
-- P0 Task Hall 基础表（任务 / 接单 / 物流扩展）
-- 仅 LOGISTICS 走完整闭环，其余 task_type 预留枚举与表位。
-- ============================================================

CREATE TABLE tasks (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    publisher_id BIGINT NOT NULL,
    task_type VARCHAR(20) NOT NULL,                                  -- DRONE_OP/LOGISTICS/HAIL_RIDE/TAXI/AD
    title VARCHAR(200) NOT NULL,
    description TEXT,
    reward_amount NUMERIC(18,4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    capability_required VARCHAR(30) NOT NULL,                        -- RIDE_HAIL/TAXI/LOGISTICS/AD_DISPLAY/DRONE_OP/SWAP
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',                      -- OPEN/ASSIGNED/IN_PROGRESS/COMPLETED/SETTLED/CANCELLED/DISPUTED
    geo_lat DECIMAL(10,8),
    geo_lng DECIMAL(11,8),
    service_radius_m INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deadline_at TIMESTAMPTZ,
    assigned_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX idx_tasks_publisher ON tasks (publisher_id);
CREATE INDEX idx_tasks_status_cap ON tasks (status, capability_required);

CREATE TABLE task_assignments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES tasks(id),
    provider_id BIGINT NOT NULL,
    asset_id BIGINT REFERENCES assets(id),                           -- 接单时绑定资产，可空直到 accept
    project_id BIGINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED',
    progress_pct INT NOT NULL DEFAULT 0,
    last_progress_note VARCHAR(255),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ta_task ON task_assignments (task_id);
CREATE INDEX idx_ta_provider ON task_assignments (provider_id);
CREATE INDEX idx_ta_asset ON task_assignments (asset_id);

-- 物流任务 1:1 扩展（仅 task_type='LOGISTICS'）
CREATE TABLE task_logistics (
    task_id BIGINT PRIMARY KEY REFERENCES tasks(id),
    pickup_addr VARCHAR(255),
    dropoff_addr VARCHAR(255),
    cargo_type VARCHAR(50),
    weight_kg NUMERIC(10,2)
);
