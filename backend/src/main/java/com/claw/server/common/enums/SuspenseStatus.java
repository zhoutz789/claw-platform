package com.claw.server.common.enums;

/**
 * 差错挂账工单状态（对应 suspense_entry.status，V131 定义；详见设计 §8）。
 *
 * <p>差异分类（{@code diff_type}）→ 处理：
 * CHANNEL_EXTRA 补录 / BOOK_EXTRA 挂起核查 / AMOUNT_MISMATCH 差额追因 /
 * FX_DIFF 月度结转 / UNMATCHED 未匹配。
 */
public enum SuspenseStatus {
    /** 新建待处理。 */
    OPEN,
    /** 处理中。 */
    PROCESSING,
    /** 已处置（冲销完成）。 */
    RESOLVED,
    /** 已核销（无法追回，经审批核销）。 */
    WRITTEN_OFF
}
