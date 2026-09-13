package com.claw.server.common.enums;

/**
 * 清分场景（对应 clearing_instruction.scene，V129 定义）。
 *
 * <p>编码沿用资金路由与清分方案 v1 的 R1–R12 场景号；每个场景对应一组合适的
 * {@code settlement_rule.biz_scene}（如 R1 → CONSIGNMENT_SCAN、R5 → RENTAL_SPLIT）。
 * 场景之间共享同一套清分引擎（{@code SplitEngine} + {@code ClearingService}），
 * 差异只在「收款方集合」与「规则命中」上。
 */
public enum ClearingScene {
    /** R1 扫码购分账（寄售商品：平台服务费 + 站佣 + 物流 + 厂家残差）。 */
    R1,
    /** R2 预留场景。 */
    R2,
    /** R3 预留场景。 */
    R3,
    /** R4 预留场景。 */
    R4,
    /** R5 共享池分成（四方：所有人 / 站 / 平台 / 保险）。 */
    R5,
    /** R6 预留场景。 */
    R6,
    /** R7 预留场景。 */
    R7,
    /** R8 预留场景。 */
    R8,
    /** R9 预留场景。 */
    R9,
    /** R10 平台自有分润再分配（推广者，平台自己的钱）。 */
    R10,
    /** R11 预留场景。 */
    R11,
    /** R12 预留场景。 */
    R12
}
