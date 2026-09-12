package com.claw.server.domain.report;

import com.claw.server.common.enums.VehicleOpType;
import com.claw.server.domain.asset.AssetVehicleOps;
import com.claw.server.domain.asset.AssetVehicleOpsRepository;
import com.claw.server.domain.capacity.CapacitySubscription;
import com.claw.server.domain.capacity.CapacitySubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车辆资产收益报表服务（容量定购用户视角）。
 *
 * <p>基于既有 {@code claw.asset_vehicle_ops} 运营记录聚合毛收入，并按容量定购的分成比例
 * 计算容量用户的分成金额。本服务完全只读，不写入任何表，也不重建结算逻辑
 * （既有 BatterySettlementService / CrossBorderSettlementService 仅做单次计费计算，
 * 未提供「按资产 + 时间窗」的聚合入口，故直接复用 asset_vehicle_ops.revenue 聚合）。
 */
@Service
@RequiredArgsConstructor
public class VehicleEarningsReportService {

    /** 当容量定购未配置显式分成比例时使用的默认比例（即 100% 归容量用户）。 */
    private static final BigDecimal DEFAULT_SHARE_RATIO = BigDecimal.ONE;

    /** 后端结算默认币种（与 BatterySettlementService / LedgerService 一致）。 */
    private static final String DEFAULT_CURRENCY = "USD";

    private static final int SCALE = 2;

    private final AssetVehicleOpsRepository opsRepository;
    private final CapacitySubscriptionRepository subscriptionRepository;

    /**
     * 生成车辆资产收益报表。
     *
     * @param capacitySubscriptionId 容量定购 ID（用于读取分成比例）
     * @param assetId                资产 ID
     * @param from                   统计开始时间（含）
     * @param to                     统计结束时间（含）
     * @return 资产收益明细 + 容量用户分成
     */
    @Transactional(readOnly = true)
    public VehicleEarningsReport generate(Long capacitySubscriptionId, Long assetId, Instant from, Instant to) {
        CapacitySubscription subscription = subscriptionRepository.findById(capacitySubscriptionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "CapacitySubscription not found: " + capacitySubscriptionId));

        BigDecimal shareRatio = shareRatioOf(subscription);

        List<AssetVehicleOps> ops = opsRepository.findByAssetIdAndStartedAtBetween(assetId, from, to);

        Map<VehicleOpType, Agg> byType = new LinkedHashMap<>();
        BigDecimal grossRaw = BigDecimal.ZERO;
        for (AssetVehicleOps op : ops) {
            BigDecimal amt = op.getRevenue() == null ? BigDecimal.ZERO : op.getRevenue();
            grossRaw = grossRaw.add(amt);
            byType.computeIfAbsent(op.getOpType(), k -> new Agg(0L, BigDecimal.ZERO)).add(amt);
        }

        List<VehicleEarningsReport.EarningsLine> lines = new ArrayList<>();
        for (Map.Entry<VehicleOpType, Agg> e : byType.entrySet()) {
            lines.add(VehicleEarningsReport.EarningsLine.builder()
                    .opType(e.getKey().name())
                    .count(e.getValue().count)
                    .amount(scale(e.getValue().amount))
                    .build());
        }

        BigDecimal gross = scale(grossRaw);
        BigDecimal capacityUserShare = scale(gross.multiply(shareRatio));
        BigDecimal platformShare = gross.subtract(capacityUserShare);

        return VehicleEarningsReport.builder()
                .assetId(assetId)
                .lines(lines)
                .grossTotal(gross)
                .capacityUserShare(capacityUserShare)
                .platformShare(scale(platformShare))
                .currency(DEFAULT_CURRENCY)
                .build();
    }

    /**
     * 读取容量定购的分成比例。
     *
     * <p>当前 {@link CapacitySubscription} 实体未定义 shareRatio / share 字段
     * （仅有 unitCount / prepaidAmount / subscriberUserId），故此处默认返回 1.0
     * （100% 归容量用户）。未来实体补充分成比例字段后，改为读取该字段即可。
     */
    private static BigDecimal shareRatioOf(CapacitySubscription subscription) {
        // T7 兼容：CapacitySubscription 暂无 shareRatio 字段时默认 1.0。
        return DEFAULT_SHARE_RATIO;
    }

    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 按运营类型累加的临时聚合容器。 */
    private static final class Agg {
        private long count;
        private BigDecimal amount;

        private Agg(long count, BigDecimal amount) {
            this.count = count;
            this.amount = amount;
        }

        private void add(BigDecimal amt) {
            this.count += 1;
            this.amount = this.amount.add(amt == null ? BigDecimal.ZERO : amt);
        }
    }
}
