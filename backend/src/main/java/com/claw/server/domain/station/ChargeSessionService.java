package com.claw.server.domain.station;

import com.claw.server.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 充电会话服务（锂电池 BMS 对接方案 Phase D）。
 *
 * <p>开会话记录起始 BMS 净能量，关会话按「累计 Wh 计数器」算实际送达电量并计费：
 * 电费 = 送达电量 × 电价快照（电网/光伏 + TOU，pass-through 不加价）；
 * 服务费与换电/租还同口径（0.1–0.2 USD，平台配置 {@code BATTERY_SERVICE_FEE}），
 * 即「换电 / 租还 / 充电」三场景的统一计费单元。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChargeSessionService {

    private static final int SCALE = 4;

    private final ChargeSessionRepository sessionRepository;

    /** 开启充电会话（记录起始净能量）。 */
    @Transactional
    public ChargeSession open(Long assetId, Long stationId, String deviceNo, BigDecimal startEnergyWh) {
        ChargeSession s = ChargeSession.builder()
                .assetId(assetId).stationId(stationId).deviceNo(deviceNo)
                .status("ACTIVE").startedAt(Instant.now())
                .startEnergyWh(startEnergyWh)
                .build();
        return sessionRepository.save(s);
    }

    /** 结束充电会话：算电量 + 计费（电价与来电费为 pass-through）。 */
    @Transactional
    public ChargeSession close(Long sessionId, BigDecimal endEnergyWh,
                               BigDecimal peakPowerW, BigDecimal pricePerWh, BigDecimal serviceFee) {
        ChargeSession s = sessionRepository.findById(sessionId)
                .orElseThrow(() -> BizException.notFound("error.charge.session.not.found"));

        BigDecimal start = s.getStartEnergyWh() != null ? s.getStartEnergyWh() : BigDecimal.ZERO;
        BigDecimal end = endEnergyWh != null ? endEnergyWh : BigDecimal.ZERO;
        BigDecimal delivered = end.subtract(start).max(BigDecimal.ZERO);

        Fee fee = computeFees(delivered, pricePerWh, serviceFee);

        s.setEndEnergyWh(end);
        s.setEnergyDeliveredWh(delivered);
        s.setPeakPowerW(peakPowerW);
        s.setPricePerWh(pricePerWh);
        s.setElectricityFee(fee.electricityFee());
        s.setServiceFee(fee.serviceFee());
        s.setTotalFee(fee.total());
        s.setStatus("ENDED");
        s.setEndedAt(Instant.now());
        return sessionRepository.save(s);
    }

    /** 纯计算：电费 + 服务费（无 DB 依赖，便于单测与调度复用）。 */
    public static Fee computeFees(BigDecimal energyDeliveredWh, BigDecimal pricePerWh, BigDecimal serviceFee) {
        BigDecimal energy = energyDeliveredWh != null ? energyDeliveredWh : BigDecimal.ZERO;
        BigDecimal price = pricePerWh != null ? pricePerWh : BigDecimal.ZERO;
        BigDecimal fee = serviceFee != null ? serviceFee : BigDecimal.ZERO;
        BigDecimal electricityFee = energy.multiply(price).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal total = electricityFee.add(fee).setScale(SCALE, RoundingMode.HALF_UP);
        return new Fee(electricityFee, fee, total);
    }

    public record Fee(BigDecimal electricityFee, BigDecimal serviceFee, BigDecimal total) {
    }
}
