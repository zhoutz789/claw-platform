package com.claw.server.domain.report;

import com.claw.server.common.dto.DashboardViews;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.station.StationBatteryRepository;
import com.claw.server.domain.swap.SwapOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 数据大屏服务（S5）：聚合订单 / 电池 / 托管资金三大类指标。
 * report 域作为顶层聚合视图，跨域只读各域应用服务与仓储统计。
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final Set<AccountType> ESCROW_TYPES = Set.of(
            AccountType.RESIDUAL_RESERVE, AccountType.BATTERY_FUND, AccountType.VEHICLE_RISK);

    private final SwapOrderRepository swapOrderRepository;
    private final StationBatteryRepository stationBatteryRepository;
    private final AccountService accountService;

    @Transactional(readOnly = true)
    public DashboardViews.DashboardView dashboard() {
        long totalSwap = swapOrderRepository.count();
        long ready = stationBatteryRepository.countByStatus("READY");
        long charging = stationBatteryRepository.countByStatus("CHARGING");

        var escrow = accountService.listAccounts(null, null).stream()
                .filter(a -> ESCROW_TYPES.contains(a.accountType()))
                .map(this::toEscrow)
                .toList();

        return new DashboardViews.DashboardView(totalSwap, ready, charging, escrow);
    }

    private DashboardViews.EscrowBalanceView toEscrow(LedgerViews.AccountView a) {
        return new DashboardViews.EscrowBalanceView(a.accountType().name(), a.balance());
    }
}
