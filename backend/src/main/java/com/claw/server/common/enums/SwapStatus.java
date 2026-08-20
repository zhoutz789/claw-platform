package com.claw.server.common.enums;

/**
 * 换电订单状态机（对应 claw.swap_orders.status，技术文档 2.3）。
 *
 * <p>流转：
 * <pre>
 * CREATED(选电池/扫码) → FROZEN(押金+预扣冻结成功)
 *   → SWAPPING(出满电/收欠电, 双向押金流转事务)
 *   → SETTLED(按实际用量结算, 多退少补)
 * 任意态 → EXCEPTION(异常人工介入) / CANCELLED(解冻退回)
 * </pre>
 */
public enum SwapStatus {
    /** 已创建（选电池/扫码）。 */
    CREATED,
    /** 押金 + 预扣冻结成功。 */
    FROZEN,
    /** 换电中（满电出/欠电收，双向押金流转已完成）。 */
    SWAPPING,
    /** 已按实际用量结算（多退少补完成，电池仍在用户手中、押金继续冻结）。 */
    SETTLED,
    /** 终态（电池归还后订单闭环）。 */
    COMPLETED,
    /** 异常，人工介入。 */
    EXCEPTION,
    /** 已取消（解冻退回）。 */
    CANCELLED
}
