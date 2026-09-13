package com.claw.server.common.enums;

/**
 * 结算批次状态机（对应 settlement_batch.status，V130 定义；详见设计 §6.2）。
 *
 * <pre>
 * COLLECTING ──review──> REVIEWING ──approve──> APPROVED ──submit──> SENDING
 *                                                  │                  │
 *                                                  │                  ├─全成功──> SETTLED
 *                                                  │                  ├─部分──> PARTIAL ──> SENDING(补发)
 *                                                  │                  └─失败──> FAILED ──> MANUAL
 *                                                  └──reject──> COLLECTING（退回重汇总）
 * </pre>
 */
public enum BatchStatus {
    /** 汇总中（批次已创建，正在收集明细）。 */
    COLLECTING,
    /** 待审核。 */
    REVIEWING,
    /** 已审核通过。 */
    APPROVED,
    /** 下发中。 */
    SENDING,
    /** 全部成功（终态）。 */
    SETTLED,
    /** 部分成功（可补发回 SENDING）。 */
    PARTIAL,
    /** 失败（可转 MANUAL）。 */
    FAILED,
    /** 需人工介入（终态）。 */
    MANUAL
}
