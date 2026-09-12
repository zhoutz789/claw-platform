-- ============================================================================
-- V125 协议适配层（adapter_profiles + device_adapters）
-- 设备协议注册表 + 每设备适配器绑定 + 自动协商：新车辆/设备经 MQTT 接入时，
-- 由其握手元数据识别协议 → 匹配 profile → 把原始报文归一化为标准 telemetry 视图，
-- 复用 BMS BmsAdapter 多 profile 先例，将 OCPP/BMS/vehicle 收敛到统一 DeviceAdapter 抽象。
-- 不动 V1–V124；本迁移接在 V124 之后。
-- ============================================================================

CREATE TABLE claw.adapter_profiles (
    id                      BIGSERIAL     PRIMARY KEY,
    protocol                VARCHAR(64)   NOT NULL UNIQUE,
    parser_ref              VARCHAR(255),
    field_map_json          JSONB,
    downlink_templates_json JSONB,
    status                  VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE claw.device_adapters (
    id                   BIGSERIAL     PRIMARY KEY,
    device_id            BIGINT        NOT NULL,
    profile_id           BIGINT        NOT NULL,
    negotiated_meta_json JSONB,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_da_device  FOREIGN KEY (device_id)  REFERENCES claw.devices (id),
    CONSTRAINT fk_da_profile FOREIGN KEY (profile_id) REFERENCES claw.adapter_profiles (id),
    CONSTRAINT uq_da_device_profile UNIQUE (device_id, profile_id)
);

CREATE INDEX idx_device_adapters_device_id ON claw.device_adapters (device_id);

-- 默认通用 MQTT profile：报文即规范 JSON（边缘网关已完成物理协议→MQTT 归一化），
-- 后端仅做字段映射；下行模板为朴素 ${param} 占位替换。使 detectProtocol 的 GENERIC_MQTT 默认路径开箱可用。
INSERT INTO claw.adapter_profiles (protocol, parser_ref, field_map_json, downlink_templates_json, status)
VALUES (
    'GENERIC_MQTT',
    'com.claw.server.domain.adapter.GenericJsonAdapter',
    '{"assetId":"assetId","lat":"lat","lng":"lng","speedKph":"speed","soc":"soc","soh":"soh","temp":"temp"}',
    '{"SET_SPEED":"speed=${speed}","PING":"ping=1"}',
    'ACTIVE'
) ON CONFLICT (protocol) DO NOTHING;
