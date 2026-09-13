package com.claw.server.common.enums;

/**
 * 清分时机模式（对应 clearing_instruction.mode，V129 定义；详见设计 §7）。
 *
 * <p>首选 {@link #AT_SOURCE}（分账 at source，平台零存量）；通道不支持原生分账时
 * 依次回退 {@link #ON_ARRIVAL}（到账即清）与 {@link #BATCH}（周期批量 T+N）。
 */
public enum ClearingMode {
    /** 分账 at source：通道侧一笔收款按规则直拆到多收款方。⚠️【待通道确认】。 */
    AT_SOURCE,
    /** 到账即清：收单到账事件触发（用于验收窗暂存后清分），准实时。 */
    ON_ARRIVAL,
    /** 周期批量：结算周期到点后汇总批次下发（货款 T+7 / 平台自有 T+30）。 */
    BATCH
}
