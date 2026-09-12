package com.claw.server.domain.report;

import com.claw.server.common.enums.VehicleOpType;
import com.claw.server.domain.asset.AssetVehicleOps;
import com.claw.server.domain.asset.AssetVehicleOpsRepository;
import com.claw.server.domain.capacity.CapacitySubscription;
import com.claw.server.domain.capacity.CapacitySubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * VehicleEarningsReportService 单元测试（纯 Mockito，不连 DB / 不加载 Spring）。
 *
 * <p>校验：分 opType 聚合明细、grossTotal、币种默认，以及 capacityUserShare 应用了分成比例。
 * 因当前 CapacitySubscription 实体无 shareRatio 字段，服务默认 shareRatio = 1.0，
 * 故 capacityUserShare 等于 grossTotal、platformShare 为 0。
 */
@ExtendWith(MockitoExtension.class)
class VehicleEarningsReportServiceTest {

    @Mock
    private AssetVehicleOpsRepository opsRepository;

    @Mock
    private CapacitySubscriptionRepository subscriptionRepository;

    @InjectMocks
    private VehicleEarningsReportService service;

    private AssetVehicleOps op(Long id, VehicleOpType type, BigDecimal revenue) {
        return AssetVehicleOps.builder()
                .id(id)
                .assetId(10L)
                .opType(type)
                .revenue(revenue)
                .startedAt(Instant.now())
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void generate_breaks_down_by_op_type_and_applies_share_ratio() {
        when(opsRepository.findByAssetIdAndStartedAtBetween(eq(10L), any(), any()))
                .thenReturn(List.of(
                        op(1L, VehicleOpType.PASSENGER, new BigDecimal("100.00")),
                        op(2L, VehicleOpType.PASSENGER, new BigDecimal("50.00")),
                        op(3L, VehicleOpType.LOGISTICS, new BigDecimal("40.00"))));

        CapacitySubscription sub = CapacitySubscription.builder()
                .id(5L)
                .subscriberUserId(99L)
                .build();
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(sub));

        VehicleEarningsReport report = service.generate(
                5L, 10L, Instant.now().minusSeconds(3600), Instant.now());

        // 2 行明细：PASSENGER(2 次, 150) + LOGISTICS(1 次, 40)
        assertEquals(2, report.getLines().size());
        VehicleEarningsReport.EarningsLine passenger = report.getLines().stream()
                .filter(l -> "PASSENGER".equals(l.getOpType())).findFirst().orElseThrow();
        assertEquals(2L, passenger.getCount());
        assertEquals(0, passenger.getAmount().compareTo(new BigDecimal("150.00")));

        // 毛收入合计 = 190.00
        assertEquals(0, report.getGrossTotal().compareTo(new BigDecimal("190.00")));

        // 默认 shareRatio = 1.0 → capacityUserShare == grossTotal，platformShare == 0
        assertEquals(0, report.getCapacityUserShare().compareTo(new BigDecimal("190.00")));
        assertEquals(0, report.getPlatformShare().compareTo(BigDecimal.ZERO));
        assertEquals("USD", report.getCurrency());
        assertEquals(10L, report.getAssetId());
    }

    @Test
    void generate_returns_empty_lines_when_no_ops() {
        when(opsRepository.findByAssetIdAndStartedAtBetween(eq(10L), any(), any()))
                .thenReturn(List.of());
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(
                CapacitySubscription.builder().id(5L).subscriberUserId(99L).build()));

        VehicleEarningsReport report = service.generate(
                5L, 10L, Instant.now().minusSeconds(3600), Instant.now());

        assertEquals(0, report.getLines().size());
        assertEquals(0, report.getGrossTotal().compareTo(BigDecimal.ZERO));
        assertEquals(0, report.getCapacityUserShare().compareTo(BigDecimal.ZERO));
    }
}
