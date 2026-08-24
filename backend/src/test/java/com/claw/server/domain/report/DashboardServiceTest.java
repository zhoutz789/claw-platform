package com.claw.server.domain.report;

import com.claw.server.common.dto.DashboardViews;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.manufacturer.ManufacturerRepository;
import com.claw.server.domain.manufacturer.ProductRepository;
import com.claw.server.domain.manufacturer.ProductSkuRepository;
import com.claw.server.domain.manufacturer.PurchaseOrderRepository;
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
 * 数据大屏单元测试：订单/电池/三专户余额/厂家商品资产聚合，非托管账户被过滤。
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private SwapOrderRepository swapOrderRepository;
    @Mock private StationBatteryRepository stationBatteryRepository;
    @Mock private AccountService accountService;
    @Mock private ManufacturerRepository manufacturerRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductSkuRepository productSkuRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private AssetRepository assetRepository;
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
        // 厂家/商品/SKU/资产/采购：默认空计数，避免未 mock 字段 NPE
        when(manufacturerRepository.count()).thenReturn(5L);
        when(productRepository.count()).thenReturn(8L);
        when(productSkuRepository.count()).thenReturn(13L);
        when(assetRepository.count()).thenReturn(42L);
        when(purchaseOrderRepository.findAll()).thenReturn(List.of());
        when(assetRepository.countGroupByStatus()).thenReturn(List.of());

        DashboardViews.DashboardView v = service.dashboard();

        assertEquals(1284L, v.totalSwapOrders());
        assertEquals(12L, v.readyBatteries());
        assertEquals(9L, v.chargingBatteries());
        // 仅三专户（排除 MASTER）
        assertEquals(2, v.escrowAccounts().size());
        assertTrue(v.escrowAccounts().stream()
                .anyMatch(e -> "RESIDUAL_RESERVE".equals(e.escrowType())));
        // 新增聚合指标口径正确
        assertEquals(5L, v.manufacturerCount());
        assertEquals(8L, v.productCount());
        assertEquals(13L, v.skuCount());
        assertEquals(42L, v.assetCount());
        assertEquals(BigDecimal.ZERO, v.purchaseTotal());
        assertEquals(0, v.assetByStage().size());
    }
}
