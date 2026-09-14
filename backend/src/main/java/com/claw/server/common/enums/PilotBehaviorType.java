package com.claw.server.common.enums;

/**
 * 飞手行为事件类型（对应 claw.pilot_behavior_event.event_type）。
 *
 * <p>行为监控由遥测链派生：越界 / 超载 / 失联 / 超时未作业 / 危险操作，
 * 事件本身只「记录 + 评级」，是否处罚由运营侧按 {@link PilotPenaltyType} 决策。
 */
public enum PilotBehaviorType {

    /** 越界（飞出获批空域/围栏）。 */
    GEOFENCE_HIT("drone.pilot.behavior.geofence_hit"),
    /** 超载（载荷超机型上限）。 */
    OVERLOAD("drone.pilot.behavior.overload"),
    /** 链路失联（失联超时）。 */
    LOST_LINK("drone.pilot.behavior.lost_link"),
    /** 作业超时未执行/未归巢。 */
    TIMEOUT_OPERATION("drone.pilot.behavior.timeout_operation"),
    /** 其他危险操作（未按作业模版施工等）。 */
    UNSAFE_OP("drone.pilot.behavior.unsafe_op");

    private final String i18nKey;

    PilotBehaviorType(String i18nKey) {
        this.i18nKey = i18nKey;
    }

    /** 展示用 i18n key（由前端按语言解析）。 */
    public String i18nKey() {
        return i18nKey;
    }

    /**
     * 按名称或 i18n key 解析枚举（大小写不敏感，兼容前端直传枚举名）。
     *
     * @param code 枚举名或 i18n key
     * @return 匹配的枚举值
     * @throws IllegalArgumentException 无匹配时抛出（调用方转 {@code BizException}）
     */
    public static PilotBehaviorType fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("behavior type is blank");
        }
        String normalized = code.trim();
        for (PilotBehaviorType type : values()) {
            if (type.name().equalsIgnoreCase(normalized) || type.i18nKey.equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown pilot behavior type: " + code);
    }
}
