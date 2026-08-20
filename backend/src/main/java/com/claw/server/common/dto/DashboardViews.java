package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.util.List;

/** 报表/大屏视图（report 出参）。 */
public final class DashboardViews {

    private DashboardViews() {
    }

    /** 数据大屏聚合（S5）。 */
    public record DashboardView(
            long totalSwapOrders,       // 总换电单
            long readyBatteries,        // 满电电池数
            long chargingBatteries,     // 充电中电池数
            List<EscrowBalanceView> escrowAccounts) {  // 三专户余额
    }

    public record EscrowBalanceView(String escrowType, BigDecimal balance) {
    }
}
