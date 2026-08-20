package com.claw.server.domain.report;

import com.claw.server.common.dto.DashboardViews;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.station.StationBatteryRepository;
import com.claw.server.domain.swap.SwapOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 数据大屏单元测试：订单/电池/三专户余额聚合，非托管账户被过滤。
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private SwapOrderRepository swapOrderRepository;
    @Mock private StationBatteryRepository stationBatteryRepository;
    @Mock private AccountService accountService;
    @InjectMocks private DashboardService service;

    @Test
    void dashboard_aggregates_metrics_and_filters_escrow() {
        when(swapOrderRepository.count()).thenReturn(1284L);
        when(stationBatteryRepository.countByStatus("READY")).thenReturn(12L);
        when(stationBatteryRepository.countByStatus("CHARGING")).thenReturn(9L);
        when(accountService.listAccounts(null, null)).thenReturn(List.of(
                new LedgerViews.AccountView(1L, null, AccountType.MASTER, "USD", new BigDecimal("0"), BigDecimal.ZERO),
                new LedgerViews.AccountView(2L, null, AccountType.RESIDUAL_RESERVE, "USD", new BigDecimal("1204"), BigDecimal.ZERO),
                new LedgerViews.AccountView(3L, null, AccountType.BATTERY_FUND, "USD", new BigDecimal("863"), BigDecimal.ZERO)));

        DashboardViews.DashboardView v = service.dashboard();

        assertEquals(1284L, v.totalSwapOrders());
        assertEquals(12L, v.readyBatteries());
        assertEquals(9L, v.chargingBatteries());
        // 仅三专户（排除 MASTER）
        assertEquals(2, v.escrowAccounts().size());
        assertTrue(v.escrowAccounts().stream()
                .anyMatch(e -> "RESIDUAL_RESERVE".equals(e.escrowType())));
    }
}
