package com.claw.server.domain.iot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BMS 下行控制指令枚举（锂电池 BMS 对接方案 Phase C，对齐方案 §4.3）。
 *
 * <p>每条指令经既有 {@link DeviceCommandService} 签名 + 审计 + 经已签名的 {@code claw/iot/{deviceNo}/down}
 * 下发；设备执行后回 {@code cmd_ack}（{@code EmqxMqttInboundAdapter} 已按 msgType=cmd_ack 路由到
 * {@code DeviceCommandService#handleAck}）。
 *
 * <p>下发参数约定（与设备端对齐）：
 * <ul>
 *   <li>开关/触发类：{@code {"state": 1|0}}</li>
 *   <li>{@code SET_HEATER_TEMP}：{@code {"targetTemp": <℃>}}</li>
 *   <li>{@code SET_FAN_SPEED}：{@code {"fanSpeed": <0-100>}}</li>
 * </ul>
 * action 名称均 ≤16 字符，适配 {@code device_commands.action} 列长度约束。
 */
public enum BmsCommand {

    CHARGE_ENABLE("CHARGE_ENABLE", Kind.ON_OFF, 1),
    CHARGE_DISABLE("CHARGE_DISABLE", Kind.ON_OFF, 0),
    DISCHARGE_ENABLE("DISCHARGE_ENABLE", Kind.ON_OFF, 1),
    DISCHARGE_DISABLE("DISCHARGE_DISABLE", Kind.ON_OFF, 0),
    HEATER_ON("HEATER_ON", Kind.ON_OFF, 1),
    HEATER_OFF("HEATER_OFF", Kind.ON_OFF, 0),
    COOLING_ON("COOLING_ON", Kind.ON_OFF, 1),
    COOLING_OFF("COOLING_OFF", Kind.ON_OFF, 0),
    FORCE_BALANCE("FORCE_BALANCE", Kind.TRIGGER, 1),
    LOCK_SLOT("LOCK_SLOT", Kind.TRIGGER, 1),
    SET_HEATER_TEMP("SET_HEATER_TEMP", Kind.TEMP, null),
    SET_FAN_SPEED("SET_FAN_SPEED", Kind.SPEED, null);

    public final String action;
    private final Kind kind;
    private final Integer stateBit;

    BmsCommand(String action, Kind kind, Integer stateBit) {
        this.action = action;
        this.kind = kind;
        this.stateBit = stateBit;
    }

    /** 由 action 字符串解析枚举（REST 入参用）。 */
    public static BmsCommand fromAction(String action) {
        for (BmsCommand c : values()) {
            if (c.action.equalsIgnoreCase(action)) return c;
        }
        throw new IllegalArgumentException("未知 BMS 指令: " + action);
    }

    /** 构造下行 params（与设备端约定）。userParams 为可选的用户传入参数。 */
    public Map<String, Object> toParams(Map<String, Object> userParams) {
        Map<String, Object> p = new LinkedHashMap<>();
        switch (kind) {
            case ON_OFF, TRIGGER -> p.put("state", stateBit);
            case TEMP -> {
                Object v = userParams != null ? userParams.get("targetTemp") : null;
                if (v == null) throw new IllegalArgumentException(SET_HEATER_TEMP.action + " 需要 targetTemp 参数");
                p.put("targetTemp", v);
            }
            case SPEED -> {
                Object v = userParams != null ? userParams.get("fanSpeed") : null;
                if (v == null) throw new IllegalArgumentException(SET_FAN_SPEED.action + " 需要 fanSpeed 参数");
                p.put("fanSpeed", v);
            }
        }
        return p;
    }

    private enum Kind { ON_OFF, TRIGGER, TEMP, SPEED }
}
