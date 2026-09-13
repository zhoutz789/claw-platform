package com.claw.server.common.enums;

/**
 * 清分指令状态机（对应 clearing_instruction.status，V129 定义；详见设计 §6.1）。
 *
 * <pre>
 * CREATED ──send──> SENT ──ack──> ACKED ──settle──> SETTLED
 *                     │             │
 *                     └──fail──> FAILED ──retry(&le;3)──> RETRY ──> SENT
 *                                   │
 *                                   └──exhaust/需人工──> MANUAL ──(admin)──> SENT | SETTLED
 * </pre>
 *
 * <p>终态：{@link #SETTLED} / {@link #MANUAL}。
 */
public enum ClearingStatus {
    /** 已创建（账本权益已实时，通道动作待发起）。 */
    CREATED,
    /** 已下发通道。 */
    SENT,
    /** 通道已回执（机构回执号已回填）。 */
    ACKED,
    /** 已结清（终态）。 */
    SETTLED,
    /** 下发失败。 */
    FAILED,
    /** 重试中（FAILED → RETRY → SENT，&le;3 次）。 */
    RETRY,
    /** 需人工介入（终态，等待 admin 幂等同步）。 */
    MANUAL
}
