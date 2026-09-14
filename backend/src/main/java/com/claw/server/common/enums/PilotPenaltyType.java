package com.claw.server.common.enums;

/**
 * 飞手违规处罚类型（对应 claw.pilot_penalty.penalty_type）。
 *
 * <p>处罚层级递进：警告 → 罚款 → 停用 → 吊销。落库为处罚历史（可审计、可申诉），
 * 并对飞手档案状态产生确定性副作用（见 {@code PilotOpsService#penalize}）：
 * <ul>
 *   <li>{@link #WARN} / {@link #FINE}：扣减信用分，档案状态不变；</li>
 *   <li>{@link #SUSPEND}：档案状态 → {@link PilotStatus#SUSPENDED}（临时停用）；</li>
 *   <li>{@link #REVOKE}：档案状态 → {@link PilotStatus#BANNED}（永久吊销）。</li>
 * </ul>
 */
public enum PilotPenaltyType {

    /** 警告（口头/书面，扣少量信用分）。 */
    WARN("drone.pilot.penalty.warn"),
    /** 罚款。 */
    FINE("drone.pilot.penalty.fine"),
    /** 停用（临时，至 effectiveTo 或人工解除）。 */
    SUSPEND("drone.pilot.penalty.suspend"),
    /** 吊销（永久）。 */
    REVOKE("drone.pilot.penalty.revoke");

    private final String i18nKey;

    PilotPenaltyType(String i18nKey) {
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
    public static PilotPenaltyType fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("penalty type is blank");
        }
        String normalized = code.trim();
        for (PilotPenaltyType type : values()) {
            if (type.name().equalsIgnoreCase(normalized) || type.i18nKey.equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown pilot penalty type: " + code);
    }
}
