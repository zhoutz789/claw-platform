package com.claw.server.common.enums;

/** 无人车安全态（对应 claw.autonomy_modules.safety_state），LOCKED 即禁行。 */
public enum SafetyState {
    NORMAL,
    DEGRADED,
    LOCKED
}
