package com.claw.server.domain.report;

import com.claw.server.common.dto.DashboardViews;
import com.claw.server.common.dto.DashboardViews.MetricSourceView;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.manufacturer.ManufacturerRepository;
import com.claw.server.domain.manufacturer.ProductRepository;
import com.claw.server.domain.manufacturer.ProductSkuRepository;
import com.claw.server.domain.manufacturer.PurchaseOrderRepository;
import com.claw.server.domain.station.StationBatteryRepository;
import com.claw.server.domain.swap.SwapOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * 数据大屏服务：跨域只读聚合。所有指标均绑定真实库表，并在 {@code sources} 标注口径。
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final Set<AccountType> ESCROW_TYPES = Set.of(
            AccountType.RESIDUAL_RESERVE, AccountType.BATTERY_FUND, AccountType.VEHICLE_RISK);
    private static final Set<String> PAID_STATUSES = Set.of("PAID", "SHIPPED");

    private final SwapOrderRepository swapOrderRepository;
    private final StationBatteryRepository stationBatteryRepository;
    private final AccountService accountService;
    private final ManufacturerRepository manufacturerRepository;
    private final ProductRepository productRepository;
    private final ProductSkuRepository productSkuRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final AssetRepository assetRepository;

    @Transactional(readOnly = true)
    public DashboardViews.DashboardView dashboard() {
        long totalSwap = swapOrderRepository.count();
        long ready = stationBatteryRepository.countByStatus("READY");
        long charging = stationBatteryRepository.countByStatus("CHARGING");

        var escrow = accountService.listAccounts(null, null).stream()
                .filter(a -> ESCROW_TYPES.contains(a.accountType()))
                .map(a -> new DashboardViews.EscrowBalanceView(a.accountType().name(), a.balance()))
                .toList();

        long manufacturerCount = manufacturerRepository.count();
        long productCount = productRepository.count();
        long skuCount = productSkuRepository.count();
        long assetCount = assetRepository.count();
        BigDecimal purchaseTotal = purchaseOrderRepository.findAll().stream()
                .filter(o -> PAID_STATUSES.contains(o.getStatus()))
                .map(o -> o.getTotalAmount() == null ? BigDecimal.ZERO : o.getTotalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Long> assetByStage = new LinkedHashMap<>();
        for (Object[] row : assetRepository.countGroupByStatus()) {
            AssetStatus st = (AssetStatus) row[0];
            long c = ((Number) row[1]).longValue();
            assetByStage.put(st == null ? "UNKNOWN" : st.name(), c);
        }

        List<MetricSourceView> sources = List.of(
                new MetricSourceView("totalSwapOrders", "累计换电订单", "claw.swap_orders", "全量计数"),
                new MetricSourceView("readyBatteries", "满电电池", "claw.station_batteries(status=READY)", "按状态计数"),
                new MetricSourceView("chargingBatteries", "充电中电池", "claw.station_batteries(status=CHARGING)", "按状态计数"),
                new MetricSourceView("escrowAccounts", "三专户余额", "claw.ledger_accounts", "残值/电池基金/车辆风险专户"),
                new MetricSourceView("manufacturerCount", "厂家数", "claw.manufacturers", "未删除计数"),
                new MetricSourceView("productCount", "商品数", "claw.products", "未删除计数"),
                new MetricSourceView("skuCount", "SKU数", "claw.product_skus", "未删除计数"),
                new MetricSourceView("assetCount", "资产总数", "claw.assets", "全量计数"),
                new MetricSourceView("assetByStage", "资产状态分布", "claw.assets(status)", "按运营状态分组"),
                new MetricSourceView("purchaseTotal", "累计采购额", "claw.purchase_orders(PAID/SHIPPED)", "已支付订单金额合计")
        );

        return new DashboardViews.DashboardView(totalSwap, ready, charging, escrow,
                manufacturerCount, productCount, skuCount, assetCount, assetByStage, purchaseTotal, sources);
    }
}
