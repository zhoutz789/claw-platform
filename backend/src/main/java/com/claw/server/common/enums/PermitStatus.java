package com.claw.server.common.enums;

/**
 * 无人机空域许可状态（对应 claw.drone_airspace_permits.status）。
 *
 * <p>生命周期：签发即 {@link #ACTIVE}；到期或被吊销后转 {@link #EXPIRED} / {@link #REVOKED}。
 * {@code PermitGate} 只认 {@link #ACTIVE} 且时间窗覆盖当前时刻的许可。
 */
public enum PermitStatus {
    /** 有效。 */
    ACTIVE,
    /** 已过期（valid_to 已过）。 */
    EXPIRED,
    /** 已吊销（运营/监管主动撤回）。 */
    REVOKED
}
