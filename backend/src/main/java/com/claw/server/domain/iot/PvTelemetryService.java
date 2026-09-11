package com.claw.server.domain.iot;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.PvTelemetryReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 光伏遥测落库服务（光伏数据链路切片）。
 *
 * <p>把 {@link PvAdapter} 归一化的 {@link PvTelemetryReport} 写入：
 * <ul>
 *   <li>{@code telemetry_latest}：覆盖光伏实时列（交流侧/直流侧/电表/气象/发电量/状态），
 *       仅写非空字段，避免把"未上报"误覆盖为 null。</li>
 *   <li>{@code pv_generation_hourly}：小时电量，<b>只认累计计数器差分</b>，绝不用功率积分。</li>
 * </ul>
 *
 * <p><b>电量口径（本切片的核心不变量）：</b>
 * <ul>
 *   <li>INVERTER 来源：以逆变器累计发电量 {@code totalYieldWh} 为唯一口径；</li>
 *   <li>METER 来源：以电表正向有功总电能 {@code forwardTotalWh} 为唯一口径；</li>
 *   <li>两者分 source 各存一行，永不在同一行内混算。</li>
 * </ul>
 *
 * <p><b>回退保护：</b>若新累计读数 &lt; 上次读数（换表 / 清零 / 厂家 bug），
 * 冻结该小时差分（电量不增不减，绝不为负）并记 warn 日志，同时把表底读数前移到新值，
 * 使后续正常上报能继续正确差分（不会因一次回退而永久卡死）。
 *
 * <p>纯函数（{@link #bucketOf} / {@link #diffEnergy} / {@link #countersBySource}）不依赖
 * Spring 与数据库，便于单测直接验证计量逻辑。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PvTelemetryService {

    /** 逆变器来源：以逆变器累计发电量计数器为电量口径。 */
    public static final String SOURCE_INVERTER = "INVERTER";
    /** 电表来源：以电表正向有功总电能计数器为电量口径。 */
    public static final String SOURCE_METER = "METER";

    private final DeviceRepository deviceRepository;
    private final TelemetryLatestRepository telemetryLatestRepository;
    private final PvGenerationHourlyRepository pvGenerationHourlyRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public void handleReport(PvTelemetryReport r, String topicDeviceNo) {
        String deviceNo = r.deviceNo() != null ? r.deviceNo() : topicDeviceNo;
        if (deviceNo == null || deviceNo.isBlank()) {
            throw BizException.invalidParam("error.pv.device.no.missing");
        }
        Device device = deviceRepository.findByDeviceNo(deviceNo)
                .orElseThrow(() -> BizException.notFound("error.pv.device.not.found"));

        device.setLastOnlineAt(Instant.now());
        device.setUpdatedAt(Instant.now());
        deviceRepository.save(device);

        TelemetryLatest latest = telemetryLatestRepository.findByDeviceId(device.getId())
                .orElseGet(() -> TelemetryLatest.builder()
                        .deviceId(device.getId()).assetId(device.getAssetId()).build());

        latest.setAssetId(device.getAssetId());
        latest.setReportedAt(Instant.now());

        // 交流侧
        set(latest::setAcVoltageA, r.acVoltageA());
        set(latest::setAcVoltageB, r.acVoltageB());
        set(latest::setAcVoltageC, r.acVoltageC());
        set(latest::setAcCurrentA, r.acCurrentA());
        set(latest::setAcCurrentB, r.acCurrentB());
        set(latest::setAcCurrentC, r.acCurrentC());
        set(latest::setAcFrequency, r.acFrequency());
        set(latest::setAcActivePowerW, r.acActivePowerW());
        set(latest::setAcReactivePowerVar, r.acReactivePowerVar());
        set(latest::setPowerFactor, r.powerFactor());

        // 直流侧
        set(latest::setDcVoltage1, r.dcVoltage1());
        set(latest::setDcVoltage2, r.dcVoltage2());
        set(latest::setDcVoltage3, r.dcVoltage3());
        set(latest::setDcVoltage4, r.dcVoltage4());
        set(latest::setDcCurrent1, r.dcCurrent1());
        set(latest::setDcCurrent2, r.dcCurrent2());
        set(latest::setDcCurrent3, r.dcCurrent3());
        set(latest::setDcCurrent4, r.dcCurrent4());
        set(latest::setDcPowerW1, r.dcPowerW1());
        set(latest::setDcPowerW2, r.dcPowerW2());
        set(latest::setDcPowerW3, r.dcPowerW3());
        set(latest::setDcPowerW4, r.dcPowerW4());
        set(latest::setStringCurrentsJson, toJson(r.stringCurrents()));

        // 电表
        set(latest::setMeterActivePowerW, r.meterActivePowerW());
        set(latest::setForwardTotalWh, r.forwardTotalWh());
        set(latest::setReverseTotalWh, r.reverseTotalWh());
        set(latest::setDemandW, r.demandW());

        // 气象
        set(latest::setIrradiance, r.irradiance());
        set(latest::setModuleTemp, r.moduleTemp());
        set(latest::setAmbientTemp, r.ambientTemp());
        set(latest::setWindSpeed, r.windSpeed());
        set(latest::setDailyIrradiation, r.dailyIrradiation());

        // 发电量累计计数器
        set(latest::setDailyYieldWh, r.dailyYieldWh());
        set(latest::setTotalYieldWh, r.totalYieldWh());

        // 状态
        set(latest::setInverterState, r.inverterState());
        set(latest::setFaultCode, r.faultCode());
        set(latest::setDeratePercent, r.deratePercent());
        set(latest::setInternalTemp, r.internalTemp());
        set(latest::setHeatsinkTemp, r.heatsinkTemp());
        set(latest::setEfficiency, r.efficiency());

        telemetryLatestRepository.save(latest);

        Instant reportedAt = toInstant(r.timestamp());
        if (reportedAt == null) {
            reportedAt = Instant.now();
        }
        writeHourly(device, deviceNo, r, reportedAt);

        log.info("光伏遥测上报 deviceNo={} asset={} state={} acW={} meterW={} totalYieldWh={} forwardTotalWh={}",
                deviceNo, device.getAssetId(), r.inverterState(), r.acActivePowerW(),
                r.meterActivePowerW(), r.totalYieldWh(), r.forwardTotalWh());
    }

    /**
     * 小时电量落库：只认累计计数器差分，绝不积分功率。
     *
     * <p>某来源无累计计数器时整条跳过（宁缺勿假）；某小时首个样本只建表底基线、电量记 0。
     */
    private void writeHourly(Device device, String deviceNo, PvTelemetryReport r, Instant reportedAt) {
        Map<String, BigDecimal> counters = countersBySource(r);
        if (counters.isEmpty()) {
            return;
        }
        Instant bucketAt = bucketOf(reportedAt);

        for (Map.Entry<String, BigDecimal> entry : counters.entrySet()) {
            String source = entry.getKey();
            BigDecimal cumulative = entry.getValue();

            PvGenerationHourly row = pvGenerationHourlyRepository
                    .findByDeviceNoAndBucketAtAndSource(deviceNo, bucketAt, source)
                    .orElseGet(() -> PvGenerationHourly.builder()
                            .stationAssetId(device.getAssetId())
                            .deviceNo(deviceNo)
                            .source(source)
                            .bucketAt(bucketAt)
                            .energyWh(BigDecimal.ZERO)
                            .build());

            BigDecimal delta = diffEnergy(cumulative, row.getCumulativeWh());
            if (delta == null) {
                if (row.getCumulativeWh() != null && cumulative.compareTo(row.getCumulativeWh()) < 0) {
                    // 回退（换表/清零/厂家 bug）：冻结本小时差分，绝不为负；表底读数前移以免后续永久卡死。
                    log.warn("光伏累计电量回退，冻结该小时差分：deviceNo={} source={} bucket={} lastWh={} nowWh={}",
                            deviceNo, source, bucketAt, row.getCumulativeWh(), cumulative);
                }
                row.setCumulativeWh(cumulative);
                pvGenerationHourlyRepository.save(row);
                continue;
            }

            BigDecimal energy = row.getEnergyWh() == null ? BigDecimal.ZERO : row.getEnergyWh();
            row.setEnergyWh(energy.add(delta));
            row.setCumulativeWh(cumulative);
            row.setPeakPowerW(maxOf(row.getPeakPowerW(), peakPowerOf(r, source)));
            if (r.irradiance() != null) {
                row.setIrradianceAvg(r.irradiance());
            }
            // pr 需资产铭牌装机容量，本切片不计算（见 PvGenerationHourly#pr 注释）。
            pvGenerationHourlyRepository.save(row);
        }
    }

    /**
     * 小时桶：把上报时刻截断到整点（UTC）。
     *
     * @param ts 上报时刻
     * @return 该时刻所属小时桶的起点
     */
    public static Instant bucketOf(Instant ts) {
        return ts.truncatedTo(ChronoUnit.HOURS);
    }

    /**
     * 累计计数器差分：本小时新增电量。
     *
     * @param current 本次累计读数
     * @param last    上次已存累计读数（null = 本小时首个样本，无差分基准）
     * @return 新增电量 Wh；无基准时返回 {@code null}（只建基线、不计电量）；
     *         回退（current &lt; last）时返回 {@code null}（调用方冻结差分，绝不产生负电量）
     */
    public static BigDecimal diffEnergy(BigDecimal current, BigDecimal last) {
        if (current == null || last == null) {
            return null;
        }
        if (current.compareTo(last) < 0) {
            return null;
        }
        return current.subtract(last);
    }

    /**
     * 按来源拆分累计计数器：INVERTER 取逆变器总发电量，METER 取电表正向有功总电能。
     *
     * <p>两个来源各成一行，永不混算。缺失的来源不参与（宁缺勿假）。
     *
     * @param r 规范报文
     * @return source → 累计读数 Wh 的有序映射（可能为空）
     */
    public static Map<String, BigDecimal> countersBySource(PvTelemetryReport r) {
        Map<String, BigDecimal> counters = new LinkedHashMap<>();
        if (r.totalYieldWh() != null) {
            counters.put(SOURCE_INVERTER, r.totalYieldWh());
        }
        if (r.forwardTotalWh() != null) {
            counters.put(SOURCE_METER, r.forwardTotalWh());
        }
        return counters;
    }

    /** 该来源对应的瞬时功率（用于小时峰值）：INVERTER 取交流有功，METER 取电表有功。 */
    private BigDecimal peakPowerOf(PvTelemetryReport r, String source) {
        BigDecimal p = SOURCE_METER.equals(source) ? r.meterActivePowerW() : r.acActivePowerW();
        return p == null ? null : p.abs();
    }

    private BigDecimal maxOf(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) >= 0 ? a : b;
    }

    @FunctionalInterface
    private interface Setter<T> {
        void accept(T v);
    }

    private <T> void set(Setter<T> setter, T value) {
        if (value != null) setter.accept(value);
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private Instant toInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
