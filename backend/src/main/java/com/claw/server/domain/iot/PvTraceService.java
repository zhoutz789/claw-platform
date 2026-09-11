package com.claw.server.domain.iot;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.PvTraceViews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 光伏追溯查询服务（光伏追溯切片，只读）。
 *
 * <p>回答三个问题：
 * <ol>
 *   <li>这块板子哪来的、装在哪 → {@link #moduleTrace(String)} / {@link #batchOverview(String)}</li>
 *   <li>发了多少电 → 按逆变器设备号汇总 {@code pv_generation_hourly} 的 {@code energy_wh}</li>
 *   <li>衰减多少 → {@code installed_at} + 年衰减率估算（见 {@link #estimateDegradationPct}）</li>
 * </ol>
 *
 * <p><b>不编造原则：</b>缺输入就算不出的指标一律返回 {@code null}（不是 0）：
 * 质保年限（系统无数据源）、批次 PR（无统一装机容量分母）、
 * 无铭牌容量或无辐照度时的电站 PR。
 *
 * <p><b>来源隔离：</b>INVERTER 与 METER 电量始终分列返回，绝不合并求和
 * （逆变器计发电、电表计上网，口径不同）。
 *
 * <p><b>GAP 处理：</b>辐照度缺失的小时不参与 PR 计算（既不算分子也不算分母），
 * 并在桶上置 {@code irradianceGap=true}；绝不把缺失当 0 拉低 PR。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PvTraceService {

    /** 1 年按 365.25 天计（含闰年）。 */
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365.25");

    /** 峰值日照时数换算：Σ 小时平均辐照度(W/m²) × 1h ÷ 1000 = kWh/m²。 */
    private static final BigDecimal IRRADIANCE_TO_PSH_DIVISOR = new BigDecimal("1000");

    /** PR 结果保留 4 位小数。 */
    private static final int PR_SCALE = 4;

    private final PvModuleRepository pvModuleRepository;
    private final PvStationRepository pvStationRepository;
    private final PvGenerationHourlyRepository pvGenerationHourlyRepository;

    /**
     * 单块组件溯源：档案 + 所在逆变器累计发电量 + 估算衰减 + 质保剩余。
     *
     * @param serialNo 组件序列号
     * @throws BizException 组件不存在（{@code error.pv.module.not.found}）
     */
    @Transactional(readOnly = true)
    public PvTraceViews.ModuleTraceView moduleTrace(String serialNo) {
        PvModule m = pvModuleRepository.findBySerialNo(serialNo)
                .orElseThrow(() -> BizException.notFound("error.pv.module.not.found"));
        return toView(m, null);
    }

    /**
     * 批次概览：该批次组件数量 + 明细。
     *
     * @param batchNo 批次号
     * @throws BizException 批次下无组件（{@code error.pv.batch.not.found}）
     */
    @Transactional(readOnly = true)
    public PvTraceViews.BatchOverviewView batchOverview(String batchNo) {
        List<PvModule> modules = pvModuleRepository.findByBatchNoOrderBySerialNoAsc(batchNo);
        if (modules.isEmpty()) {
            throw BizException.notFound("error.pv.batch.not.found");
        }
        Map<String, BigDecimal> energyByDevice = loadEnergyByInverter(modules);
        List<PvTraceViews.ModuleTraceView> views = new ArrayList<>(modules.size());
        for (PvModule m : modules) {
            views.add(toView(m, energyByDevice));
        }
        // 批次级 PR 无统一装机容量作分母，恒为 null（不编造算法）。
        return new PvTraceViews.BatchOverviewView(batchNo, modules.size(), views, null);
    }

    /**
     * 电站发电量：按天 / 按月汇总，带 PR。
     *
     * @param stationAssetId 电站资产 ID
     * @param from           起始日（含）
     * @param to             截止日（含）
     * @throws BizException 区间非法（{@code error.pv.date.range.invalid}）
     */
    @Transactional(readOnly = true)
    public PvTraceViews.StationYieldView stationYield(Long stationAssetId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw BizException.invalidParam("error.pv.date.range.invalid");
        }
        BigDecimal ratedPowerWp = pvStationRepository.findByAssetId(stationAssetId)
                .map(PvStation::getRatedPowerWp).orElse(null);

        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<PvGenerationHourly> rows = pvGenerationHourlyRepository
                .findByStationAssetIdAndBucketAtBetweenOrderByBucketAtDesc(stationAssetId, fromInstant, toInstant);

        // 按天聚合：电量按来源分列，辐照度只取 INVERTER 行（避免与 METER 行重复计入）
        Map<LocalDate, Agg> byDay = new TreeMap<>();
        for (PvGenerationHourly r : rows) {
            LocalDate day = r.getBucketAt().atZone(ZoneOffset.UTC).toLocalDate();
            Agg agg = byDay.computeIfAbsent(day, d -> new Agg());
            BigDecimal energy = r.getEnergyWh() == null ? BigDecimal.ZERO : r.getEnergyWh();
            if (PvTelemetryService.SOURCE_METER.equals(r.getSource())) {
                agg.meterWh = agg.meterWh.add(energy);
            } else {
                agg.inverterWh = agg.inverterWh.add(energy);
                if (r.getIrradianceAvg() == null) {
                    agg.gapHours++;
                } else {
                    agg.irradianceSum = agg.irradianceSum.add(r.getIrradianceAvg());
                    agg.prInverterWh = agg.prInverterWh.add(energy);
                }
            }
        }

        List<PvTraceViews.YieldBucket> daily = new ArrayList<>(byDay.size());
        BigDecimal totalInverter = BigDecimal.ZERO;
        BigDecimal totalMeter = BigDecimal.ZERO;
        BigDecimal totalPrEnergy = BigDecimal.ZERO;
        BigDecimal totalIrradiance = BigDecimal.ZERO;
        boolean anyGap = false;
        for (Map.Entry<LocalDate, Agg> e : byDay.entrySet()) {
            Agg agg = e.getValue();
            BigDecimal psh = peakSunHours(agg.irradianceSum);
            BigDecimal pr = performanceRatio(agg.prInverterWh, ratedPowerWp, psh);
            daily.add(new PvTraceViews.YieldBucket(e.getKey().toString(), agg.inverterWh, agg.meterWh,
                    psh, pr, agg.gapHours > 0));
            totalInverter = totalInverter.add(agg.inverterWh);
            totalMeter = totalMeter.add(agg.meterWh);
            totalPrEnergy = totalPrEnergy.add(agg.prInverterWh);
            totalIrradiance = totalIrradiance.add(agg.irradianceSum);
            anyGap |= agg.gapHours > 0;
        }
        if (anyGap) {
            log.info("光伏电站 {} 发电量汇总存在辐照度缺失时段，PR 已剔除这些小时（不当 0 处理）", stationAssetId);
        }

        List<PvTraceViews.YieldBucket> monthly = monthlyBuckets(byDay, ratedPowerWp);
        BigDecimal totalPsh = peakSunHours(totalIrradiance);
        return new PvTraceViews.StationYieldView(stationAssetId, ratedPowerWp, daily, monthly,
                totalInverter, totalMeter, performanceRatio(totalPrEnergy, ratedPowerWp, totalPsh));
    }

    /**
     * 峰值日照时数 h = 各小时平均辐照度之和(W/m²) ÷ 1000。
     *
     * @param irradianceSumWm2 桶内各小时辐照度之和（仅含有效小时）
     * @return 峰值日照时数；入参为 null 时返回 null
     */
    public static BigDecimal peakSunHours(BigDecimal irradianceSumWm2) {
        if (irradianceSumWm2 == null) {
            return null;
        }
        return irradianceSumWm2.divide(IRRADIANCE_TO_PSH_DIVISOR, 6, RoundingMode.HALF_UP);
    }

    /**
     * 性能比 PR = 实际发电量 Wh ÷ (铭牌装机容量 Wp × 峰值日照时数 h)。
     *
     * @param energyWh     实际发电量（仅含辐照度有效的小时）
     * @param ratedPowerWp 铭牌装机容量
     * @param peakSunHours 峰值日照时数
     * @return PR；任一入参缺失或分母 ≤ 0 时返回 {@code null}（绝不返回 0 充数）
     */
    public static BigDecimal performanceRatio(BigDecimal energyWh, BigDecimal ratedPowerWp,
                                              BigDecimal peakSunHours) {
        if (energyWh == null || ratedPowerWp == null || peakSunHours == null) {
            return null;
        }
        if (ratedPowerWp.compareTo(BigDecimal.ZERO) <= 0 || peakSunHours.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return energyWh.divide(ratedPowerWp.multiply(peakSunHours), PR_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 估算累计衰减 % = 年衰减率 × 已装年数 × 100。
     *
     * @param installedAt 安装日期
     * @param annualRate  年衰减率（小数，如 0.0050）
     * @param today       计算基准日（便于单测固定时钟）
     * @return 累计衰减 %；任一入参缺失时返回 {@code null}
     */
    public static BigDecimal estimateDegradationPct(LocalDate installedAt, BigDecimal annualRate,
                                                    LocalDate today) {
        if (installedAt == null || annualRate == null || today == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(installedAt, today);
        if (days < 0) {
            return null;
        }
        BigDecimal years = BigDecimal.valueOf(days).divide(DAYS_PER_YEAR, 6, RoundingMode.HALF_UP);
        return annualRate.multiply(years).multiply(BigDecimal.valueOf(100))
                .setScale(PR_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 按年-月归并（period = yyyy-MM）。月 PR 用「月内辐照度有效小时电量 ÷ (Wp × 月 PSH)」重算，
     * 而不是对日 PR 求平均（日间发电量差异大，直接平均会引入加权误差）。
     */
    private List<PvTraceViews.YieldBucket> monthlyBuckets(Map<LocalDate, Agg> byDay, BigDecimal ratedPowerWp) {
        Map<String, Agg> byMonth = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, Agg> e : byDay.entrySet()) {
            Agg src = e.getValue();
            Agg agg = byMonth.computeIfAbsent(e.getKey().toString().substring(0, 7), k -> new Agg());
            agg.inverterWh = agg.inverterWh.add(src.inverterWh);
            agg.meterWh = agg.meterWh.add(src.meterWh);
            agg.irradianceSum = agg.irradianceSum.add(src.irradianceSum);
            agg.prInverterWh = agg.prInverterWh.add(src.prInverterWh);
            agg.gapHours += src.gapHours;
        }
        List<PvTraceViews.YieldBucket> out = new ArrayList<>(byMonth.size());
        for (Map.Entry<String, Agg> e : byMonth.entrySet()) {
            Agg agg = e.getValue();
            BigDecimal psh = peakSunHours(agg.irradianceSum);
            out.add(new PvTraceViews.YieldBucket(e.getKey(), agg.inverterWh, agg.meterWh, psh,
                    performanceRatio(agg.prInverterWh, ratedPowerWp, psh), agg.gapHours > 0));
        }
        return out;
    }

    /** 批量装载逆变器累计电量（一次查询，避免 N+1）。 */
    private Map<String, BigDecimal> loadEnergyByInverter(List<PvModule> modules) {
        Set<String> deviceNos = new LinkedHashSet<>();
        for (PvModule m : modules) {
            if (m.getInverterDeviceNo() != null) {
                deviceNos.add(m.getInverterDeviceNo());
            }
        }
        Map<String, BigDecimal> energy = new HashMap<>();
        if (deviceNos.isEmpty()) {
            return energy;
        }
        for (Object[] row : pvGenerationHourlyRepository.sumEnergyWhGroupByDeviceNo(
                deviceNos, PvTelemetryService.SOURCE_INVERTER)) {
            if (row != null && row.length == 2 && row[0] != null) {
                energy.put(String.valueOf(row[0]), row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1]);
            }
        }
        return energy;
    }

    private PvTraceViews.ModuleTraceView toView(PvModule m, Map<String, BigDecimal> energyByDevice) {
        BigDecimal energy = null;
        if (m.getInverterDeviceNo() != null) {
            if (energyByDevice != null) {
                energy = energyByDevice.get(m.getInverterDeviceNo());
            } else {
                energy = pvGenerationHourlyRepository.sumEnergyWhByDeviceNoAndSource(
                        m.getInverterDeviceNo(), PvTelemetryService.SOURCE_INVERTER);
            }
        }
        return new PvTraceViews.ModuleTraceView(
                m.getSerialNo(), m.getStationAssetId(), m.getProductId(), m.getSkuId(),
                m.getManufacturerId(), m.getBatchNo(), m.getPmaxW(), m.getDegradationRate(),
                m.getInstalledAt(), m.getStringId(), m.getPosition(), m.getInverterDeviceNo(),
                m.getCertificateId(), m.getElImageUrl(), energy,
                estimateDegradationPct(m.getInstalledAt(), m.getDegradationRate(), LocalDate.now()),
                null // 质保年限：系统内无数据源，恒 null（不编造）
        );
    }

    /** 聚合累加器（电量按来源分列，辐照度单独累计）。 */
    private static final class Agg {
        private BigDecimal inverterWh = BigDecimal.ZERO;
        private BigDecimal meterWh = BigDecimal.ZERO;
        private BigDecimal irradianceSum = BigDecimal.ZERO;
        /** 仅累计辐照度有效小时的电量，作为 PR 分子。 */
        private BigDecimal prInverterWh = BigDecimal.ZERO;
        private int gapHours;
    }
}
