-- ============================================================================
-- V119 OCPP：外键 + 索引（心跳 / 状态 / 报文日志）
-- 仅新建，绝不修改 V1–V117。
-- 注：ocpp_message_log 建议生产侧异步写 + 设置保留期（见 OcppMessageLogRepository 注释），
--     避免无限膨胀；索引只加速按站点/时间的排障查询。
-- ============================================================================

-- 外键：连接器 / 交易 关联到站点（站点被删 cascade 由业务层保证，这里仅约束引用完整性）
ALTER TABLE claw.ocpp_connectors
    ADD CONSTRAINT fk_ocpp_connectors_station
    FOREIGN KEY (station_id) REFERENCES claw.ocpp_charging_stations (charge_point_id);

ALTER TABLE claw.ocpp_transactions
    ADD CONSTRAINT fk_ocpp_transactions_station
    FOREIGN KEY (station_id) REFERENCES claw.ocpp_charging_stations (charge_point_id);

-- 站点状态 / 心跳 索引（离线剔除、看板过滤）
CREATE INDEX idx_ocpp_stations_status      ON claw.ocpp_charging_stations (status);
CREATE INDEX idx_ocpp_stations_heartbeat   ON claw.ocpp_charging_stations (last_heartbeat);

-- 连接器 索引
CREATE INDEX idx_ocpp_connectors_station   ON claw.ocpp_connectors (station_id);
CREATE INDEX idx_ocpp_connectors_status    ON claw.ocpp_connectors (status);

-- 交易 索引（按站点 / 状态 查活跃会话）
CREATE INDEX idx_ocpp_transactions_station ON claw.ocpp_transactions (station_id);
CREATE INDEX idx_ocpp_transactions_status  ON claw.ocpp_transactions (status);

-- 报文日志 索引（按站点 / 时间 排障，配合保留期清理）
CREATE INDEX idx_ocpp_message_log_station  ON claw.ocpp_message_log (station_id);
CREATE INDEX idx_ocpp_message_log_at       ON claw.ocpp_message_log (at);
