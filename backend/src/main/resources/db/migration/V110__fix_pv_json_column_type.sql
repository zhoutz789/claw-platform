SET search_path = claw;

-- 修正 string_currents_json 的列类型：JSON → JSONB，与 V101 同类列
-- （temperatures_json / cell_voltages_json）口径统一。
--
-- 为什么不直接改 V104：本机库可能已应用过 V104，修改已应用的迁移会导致 Flyway
-- checksum 校验失败。故按"迁移只追加"原则新增本文件；本迁移在 V104 之后执行，顺序天然正确。
-- 列当前无数据（光伏链路尚未上量），USING 转换安全；即便有数据，json → jsonb 也是无损转换。
ALTER TABLE claw.telemetry_latest
    ALTER COLUMN string_currents_json TYPE jsonb USING string_currents_json::jsonb;
