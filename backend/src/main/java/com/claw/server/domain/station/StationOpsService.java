package com.claw.server.domain.station;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.StationRequests;
import com.claw.server.common.dto.StationViews;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.swap.SwapOrder;
import com.claw.server.domain.swap.SwapOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 服务站作业服务（S4）：站方视角的扫码收发、电池位、日账单。
 *
 * <p>技术文档 API：POST /station/scan-in、/scan-out；GET /station/slots、/daily-bill。
 * <ul>
 *   <li><b>scanOut</b>：扫码发放满电电池（电池位 READY→OUT，资产 IN_STOCK→IN_USE）；</li>
 *   <li><b>scanIn</b>：扫码回收欠电电池（电池位→CHARGING，资产 IN_USE→IN_STOCK）；</li>
 *   <li><b>slots</b>：站内电池位实时状态；</li>
 *   <li><b>dailyBill</b>：当日换电营收 + 服务费三拆（基金/站/平台）+ 收发电次数。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StationOpsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Phnom_Penh");

    /** 服务费三拆（$0.32/度 = 基金 0.10 + 站 0.19 + 平台 0.03，V6 fee_rules）。 */
    private static final BigDecimal FUND_RATE = new BigDecimal("0.10");
    private static final BigDecimal STATION_RATE = new BigDecimal("0.19");
    private static final BigDecimal PLATFORM_RATE = new BigDecimal("0.03");
    private static final BigDecimal SERVICE_TOTAL = FUND_RATE.add(STATION_RATE).add(PLATFORM_RATE);

    private final StationRepository stationRepository;
    private final StationBatteryRepository stationBatteryRepository;
    private final StationHandoverOrderRepository handoverRepository;
    private final AssetRepository assetRepository;
    private final SwapOrderRepository swapOrderRepository;

    // ------------------------------------------------------------------
    // 1. 扫码发放满电电池
    // ------------------------------------------------------------------
    @Transactional
    public StationViews.HandoverView scanOut(Long stationId, StationRequests.ScanOut req, Long operatorId) {
        requireActiveStation(stationId);
        Asset battery = requireBatteryByQr(req.qrCode());

        StationBattery slot = stationBatteryRepository.findByBatteryId(battery.getId())
                .filter(s -> s.getStationId().equals(stationId))
                .orElseThrow(() -> BizException.of(40972, "error.station.battery.not.at.station"));
        if (!"READY".equals(slot.getStatus())) {
            throw BizException.of(40973, "error.station.battery.not.ready");
        }

        slot.setStatus("OUT");
        slot.setUpdatedAt(Instant.now());
        stationBatteryRepository.save(slot);

        battery.setUserId(operatorId);
        battery.setStatus(AssetStatus.IN_USE);
        battery.setUpdatedAt(Instant.now());
        assetRepository.save(battery);

        StationHandoverOrder ho = handoverRepository.save(StationHandoverOrder.builder()
                .handoverNo("HO-" + shortId()).stationId(stationId).batteryId(battery.getId())
                .opType("OUT").orderNo(req.orderNo()).operatorId(operatorId).build());

        log.info("服务站 {} 发放电池 {} (OUT)", stationId, battery.getAssetNo());
        return toView(ho, battery);
    }

    // ------------------------------------------------------------------
    // 2. 扫码回收欠电电池
    // ------------------------------------------------------------------
    @Transactional
    public StationViews.HandoverView scanIn(Long stationId, StationRequests.ScanIn req, Long operatorId) {
        requireActiveStation(stationId);
        Asset battery = requireBatteryByQr(req.qrCode());

        StationBattery slot = stationBatteryRepository.findByBatteryId(battery.getId())
                .map(s -> {
                    if (!s.getStationId().equals(stationId)) {
                        throw BizException.of(40974, "error.station.battery.other.station");
                    }
                    return s;
                })
                .orElseGet(() -> allocateSlot(stationId, battery.getId()));

        slot.setStatus("CHARGING");
        slot.setSoc(req.soc() != null ? req.soc() : BigDecimal.ZERO);
        slot.setUpdatedAt(Instant.now());
        stationBatteryRepository.save(slot);

        battery.setUserId(null);
        battery.setStatus(AssetStatus.IN_STOCK);
        battery.setUpdatedAt(Instant.now());
        assetRepository.save(battery);

        StationHandoverOrder ho = handoverRepository.save(StationHandoverOrder.builder()
                .handoverNo("HO-" + shortId()).stationId(stationId).batteryId(battery.getId())
                .opType("IN").orderNo(req.orderNo()).operatorId(operatorId).soc(slot.getSoc()).build());

        log.info("服务站 {} 回收电池 {} (IN) soc={}", stationId, battery.getAssetNo(), slot.getSoc());
        return toView(ho, battery);
    }

    // ------------------------------------------------------------------
    // 3. 电池位
    // ------------------------------------------------------------------
    @Transactional(readOnly = true)
    public List<StationViews.SlotView> slots(Long stationId) {
        return stationBatteryRepository.findByStationIdOrderBySlotNoAsc(stationId).stream()
                .map(s -> new StationViews.SlotView(s.getId(), s.getStationId(), s.getSlotNo(),
                        s.getBatteryId(), batteryNoOf(s.getBatteryId()), s.getStatus(), s.getSoc()))
                .toList();
    }

    // ------------------------------------------------------------------
    // 4. 日账单
    // ------------------------------------------------------------------
    @Transactional(readOnly = true)
    public StationViews.DailyBillView dailyBill(Long stationId, LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now(ZONE);
        Instant from = d.atStartOfDay(ZONE).toInstant();
        Instant to = d.plusDays(1).atStartOfDay(ZONE).toInstant();

        List<SwapOrder> swaps = swapOrderRepository.findByStationIdAndCreatedAtBetween(stationId, from, to);
        int swapCount = swaps.size();
        BigDecimal totalKwh = BigDecimal.ZERO;
        BigDecimal elecFee = BigDecimal.ZERO;
        BigDecimal serviceFee = BigDecimal.ZERO;
        for (SwapOrder o : swaps) {
            totalKwh = totalKwh.add(o.getActualKwh() != null ? o.getActualKwh() : o.getEstKwh());
            elecFee = elecFee.add(o.getActualElecFee() != null ? o.getActualElecFee() : o.getEstElecFee());
            serviceFee = serviceFee.add(o.getActualServiceFee() != null ? o.getActualServiceFee() : o.getEstServiceFee());
        }

        List<StationHandoverOrder> handovers =
                handoverRepository.findByStationIdAndCreatedAtBetween(stationId, from, to);
        int outCount = (int) handovers.stream().filter(h -> "OUT".equals(h.getOpType())).count();
        int inCount = (int) handovers.stream().filter(h -> "IN".equals(h.getOpType())).count();

        // 服务费三拆（基金/站/平台）
        BigDecimal fundShare = serviceFee.multiply(FUND_RATE).divide(SERVICE_TOTAL, 2, RoundingMode.HALF_UP);
        BigDecimal stationShare = serviceFee.multiply(STATION_RATE).divide(SERVICE_TOTAL, 2, RoundingMode.HALF_UP);
        BigDecimal platformShare = serviceFee.multiply(PLATFORM_RATE).divide(SERVICE_TOTAL, 2, RoundingMode.HALF_UP);
        BigDecimal totalRevenue = elecFee.add(serviceFee);

        return new StationViews.DailyBillView(stationId, d.toString(), swapCount,
                totalKwh.setScale(2, RoundingMode.HALF_UP),
                elecFee.setScale(2, RoundingMode.HALF_UP),
                serviceFee.setScale(2, RoundingMode.HALF_UP),
                totalRevenue.setScale(2, RoundingMode.HALF_UP),
                fundShare, stationShare, platformShare, outCount, inCount);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------
    private void requireActiveStation(Long stationId) {
        Station s = stationRepository.findById(stationId)
                .orElseThrow(() -> BizException.notFound("error.station.not.found"));
        if (!"ACTIVE".equals(s.getStatus())) {
            throw BizException.of(40960, "error.swap.station.inactive");
        }
    }

    private Asset requireBatteryByQr(String qrCode) {
        Asset asset = assetRepository.findByQrCode(qrCode)
                .orElseThrow(() -> BizException.notFound("error.asset.not.found"));
        if (asset.getAssetType() != AssetType.BATTERY) {
            throw BizException.of(42261, "error.swap.not.battery");
        }
        return asset;
    }

    private StationBattery allocateSlot(Long stationId, Long batteryId) {
        int nextSlot = stationBatteryRepository.findByStationIdOrderBySlotNoAsc(stationId).stream()
                .mapToInt(StationBattery::getSlotNo).max().orElse(0) + 1;
        return stationBatteryRepository.save(StationBattery.builder()
                .stationId(stationId).batteryId(batteryId).slotNo(nextSlot).status("CHARGING").build());
    }

    private String batteryNoOf(Long batteryId) {
        return assetRepository.findById(batteryId).map(Asset::getAssetNo).orElse(null);
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private StationViews.HandoverView toView(StationHandoverOrder ho, Asset battery) {
        return new StationViews.HandoverView(ho.getHandoverNo(), ho.getStationId(), ho.getBatteryId(),
                battery.getAssetNo(), ho.getOpType(), ho.getOrderNo(), ho.getOperatorId(),
                ho.getSoc(), ho.getCreatedAt());
    }
}
