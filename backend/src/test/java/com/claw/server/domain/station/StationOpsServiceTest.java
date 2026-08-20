package com.claw.server.domain.station;

import com.claw.server.common.dto.StationRequests;
import com.claw.server.common.dto.StationViews;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.swap.SwapOrder;
import com.claw.server.domain.swap.SwapOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 服务站作业单元测试：扫码发放/回收、电池位、日账单（分账三拆）。
 */
@ExtendWith(MockitoExtension.class)
class StationOpsServiceTest {

    @Mock private StationRepository stationRepository;
    @Mock private StationBatteryRepository stationBatteryRepository;
    @Mock private StationHandoverOrderRepository handoverRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private SwapOrderRepository swapOrderRepository;
    @InjectMocks private StationOpsService service;

    private Station activeStation() {
        return Station.builder().id(1L).code("PP-1").name("中央市场站").status("ACTIVE").build();
    }

    private Asset battery(Long id, String no, String qr, AssetStatus status) {
        return Asset.builder().id(id).assetNo(no).qrCode(qr)
                .assetType(AssetType.BATTERY).status(status).build();
    }

    private StationBattery slot(Long id, Long batteryId, String status) {
        return StationBattery.builder().id(id).stationId(1L).batteryId(batteryId)
                .slotNo(1).status(status).soc(new BigDecimal("100.00")).build();
    }

    private void stubStationAndBattery(String qr, Asset asset) {
        when(stationRepository.findById(1L)).thenReturn(Optional.of(activeStation()));
        when(assetRepository.findByQrCode(qr)).thenReturn(Optional.of(asset));
    }

    // ------------------------------------------------------------------
    // 扫码发放
    // ------------------------------------------------------------------
    @Test
    void scanOut_issues_ready_battery_and_writes_handover() {
        Asset b = battery(10L, "BAT-1", "QR-1", AssetStatus.IN_STOCK);
        stubStationAndBattery("QR-1", b);
        StationBattery slot = slot(1L, 10L, "READY");
        when(stationBatteryRepository.findByBatteryId(10L)).thenReturn(Optional.of(slot));
        when(stationBatteryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(handoverRepository.save(any(StationHandoverOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        StationViews.HandoverView v = service.scanOut(1L, new StationRequests.ScanOut("QR-1", null), 200L);

        assertEquals("OUT", v.opType());
        assertEquals("BAT-1", v.batteryNo());
        assertEquals("OUT", slot.getStatus());
        assertEquals(AssetStatus.IN_USE, b.getStatus());
    }

    @Test
    void scanOut_rejects_when_battery_not_at_station() {
        Asset b = battery(10L, "BAT-1", "QR-1", AssetStatus.IN_STOCK);
        stubStationAndBattery("QR-1", b);
        when(stationBatteryRepository.findByBatteryId(10L)).thenReturn(Optional.empty());

        assertThrows(Exception.class, () -> service.scanOut(1L, new StationRequests.ScanOut("QR-1", null), 200L));
        verify(handoverRepository, never()).save(any());
    }

    @Test
    void scanOut_rejects_when_battery_not_ready() {
        Asset b = battery(10L, "BAT-1", "QR-1", AssetStatus.IN_STOCK);
        stubStationAndBattery("QR-1", b);
        when(stationBatteryRepository.findByBatteryId(10L)).thenReturn(Optional.of(slot(1L, 10L, "CHARGING")));

        assertThrows(Exception.class, () -> service.scanOut(1L, new StationRequests.ScanOut("QR-1", null), 200L));
    }

    // ------------------------------------------------------------------
    // 扫码回收
    // ------------------------------------------------------------------
    @Test
    void scanIn_receives_battery_and_marks_charging() {
        Asset b = battery(10L, "BAT-1", "QR-1", AssetStatus.IN_USE);
        stubStationAndBattery("QR-1", b);
        StationBattery slot = slot(1L, 10L, "OUT");
        when(stationBatteryRepository.findByBatteryId(10L)).thenReturn(Optional.of(slot));
        when(stationBatteryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(handoverRepository.save(any(StationHandoverOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        StationViews.HandoverView v = service.scanIn(1L,
                new StationRequests.ScanIn("QR-1", new BigDecimal("42.00"), null), 200L);

        assertEquals("IN", v.opType());
        assertEquals("CHARGING", slot.getStatus());
        assertEquals(AssetStatus.IN_STOCK, b.getStatus());
        assertEquals(0, new BigDecimal("42.00").compareTo(v.soc()));
    }

    // ------------------------------------------------------------------
    // 日账单
    // ------------------------------------------------------------------
    @Test
    void dailyBill_aggregates_swap_and_splits_service_fee() {
        when(swapOrderRepository.findByStationIdAndCreatedAtBetween(eq(1L), any(), any()))
                .thenReturn(List.of(SwapOrder.builder()
                        .estKwh(new BigDecimal("2.00")).estElecFee(new BigDecimal("0.24"))
                        .estServiceFee(new BigDecimal("0.64")).build()));
        when(handoverRepository.findByStationIdAndCreatedAtBetween(eq(1L), any(), any()))
                .thenReturn(List.of(
                        StationHandoverOrder.builder().opType("OUT").build(),
                        StationHandoverOrder.builder().opType("IN").build()));

        StationViews.DailyBillView v = service.dailyBill(1L, LocalDate.of(2026, 8, 20));

        assertEquals(1, v.swapCount());
        assertEquals(0, new BigDecimal("2.00").compareTo(v.totalKwh()));
        assertEquals(0, new BigDecimal("0.88").compareTo(v.totalRevenue()));
        // 服务费三拆：基金 0.20 / 站 0.38 / 平台 0.06
        assertEquals(0, new BigDecimal("0.20").compareTo(v.fundShare()));
        assertEquals(0, new BigDecimal("0.38").compareTo(v.stationShare()));
        assertEquals(0, new BigDecimal("0.06").compareTo(v.platformShare()));
        assertEquals(1, v.outCount());
        assertEquals(1, v.inCount());
    }
}
