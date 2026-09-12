package com.claw.server.domain.pv;

import com.claw.server.common.enums.AssetType;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.iot.PvGenerationHourlyRepository;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 光伏日对账服务（VPP 切片最后一块后端）。
 *
 * <p><b>业务目标：</b>光伏追溯数据要能对外交付（客户/投资人/认证机构），必须可信。
 * 可信的根基是「同一电站里逆变器端累计发电量」与「并网点双向电表反向电量（下网=上网计量）」
 * 必须对得上；两者长期偏差超阈值，说明串线接错、偷电、某台逆变器离线、或计量故障。
 * 本服务每天比对这两个量、偏差告警。
 *
 * <p><b>口径（铁律）：</b>{@code INVERTER} 与 {@code METER} 的 {@code energy_wh} 都是
 * 「该小时发电量差分」（已在 {@code PvGenerationHourly} 统一），故直接按天 SUM 比较，
 * <b>绝不做 cumulative_wh 的二次差分</b>（会重复计算）。
 *
 * <p><b>纯业务逻辑：</b>{@link #reconcileDay(Long, LocalDate)} 不依赖任何定时框架，
 * 便于单测；落库为 upsert（同 station+day 重跑覆盖，不插重复行）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PvReconciliationService {

    /** 来源：逆变器累计发电量。 */
    public static final String SOURCE_INVERTER = "INVERTER";
    /** 来源：并网点双向电表反向电量（下网=上网计量）。 */
    public static final String SOURCE_METER = "METER";

    /** 偏差率阈值 system_config 键名。 */
    public static final String CONFIG_KEY_DEVIATION_RATE = "PV_RECONCILE_DEVIATION_RATE";
    /** 偏差率阈值默认值（5%）。读不到配置时使用。 */
    public static final BigDecimal DEFAULT_DEVIATION_RATE = new BigDecimal("0.05");

    public static final String STATUS_OK = "OK";
    public static final String STATUS_WARN = "WARN";
    public static final String STATUS_GAP = "GAP";

    /** deviation_rate 计算精度（与列 NUMERIC(6,4) 匹配，避免存储截断误差累积）。 */
    private static final int RATE_SCALE = 4;

    private final PvGenerationHourlyRepository pvHourlyRepository;
    private final PvDailyReconciliationRepository reconciliationRepository;
    private final AssetRepository assetRepository;
    private final SystemConfigRepository systemConfigRepository;

    /**
     * 单站单日对账（纯业务逻辑，便于单测；不依赖定时框架）。
     *
     * <p>流程：取全站当天 INVERTER / METER 的 {@code energy_wh} 合计 → 算偏差与偏差率
     * → 判定状态 → upsert 落库 → 返回结果。
     *
     * @param stationAssetId 光伏站资产 id
     * @param day            对账日（UTC 日期，与 bucket_at 的 UTC 截断口径一致）
     * @return 对账结果（含状态、偏差 Wh、偏差率、说明）
     */
    @Transactional
    public ReconcileResult reconcileDay(Long stationAssetId, LocalDate day) {
        Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        BigDecimal inverterSum = pvHourlyRepository.sumEnergyWhByStationAndSourceAndDay(
                stationAssetId, SOURCE_INVERTER, start, end);
        BigDecimal meterSum = pvHourlyRepository.sumEnergyWhByStationAndSourceAndDay(
                stationAssetId, SOURCE_METER, start, end);

        BigDecimal threshold = loadDeviationThreshold();

        String status;
        BigDecimal deviationWh;
        BigDecimal deviationRate;
        String note;

        boolean inverterMissing = isMissing(inverterSum);
        boolean meterMissing = isMissing(meterSum);

        if (inverterMissing || meterMissing) {
            // 任一侧无有效数据（null 或 0）→ 无法计算偏差 → GAP
            status = STATUS_GAP;
            deviationWh = null;
            deviationRate = null;
            if (inverterMissing && meterMissing) {
                note = "逆变器侧(INVERTER)与电表侧(METER)均无有效数据";
            } else if (inverterMissing) {
                note = "逆变器侧(INVERTER)无有效数据";
            } else {
                note = "电表侧(METER)无有效数据";
            }
        } else {
            deviationWh = inverterSum.subtract(meterSum);
            // meter > 0 才算偏差率，否则记 null（不除零）
            deviationRate = meterSum.compareTo(BigDecimal.ZERO) > 0
                    ? deviationWh.divide(meterSum, RATE_SCALE, RoundingMode.HALF_UP)
                    : null;
            if (deviationRate != null && deviationRate.abs().compareTo(threshold) > 0) {
                status = STATUS_WARN;
                note = String.format("偏差率 %s%% 超阈值 %s%%",
                        asPercent(deviationRate), asPercent(threshold));
            } else {
                status = STATUS_OK;
                note = "偏差率在阈值内";
            }
        }

        PvDailyReconciliation entity = reconciliationRepository
                .findByStationAssetIdAndDay(stationAssetId, day)
                .orElseGet(() -> PvDailyReconciliation.builder()
                        .stationAssetId(stationAssetId)
                        .day(day)
                        .build());
        entity.setInverterEnergyWh(inverterMissing ? null : inverterSum);
        entity.setMeterReverseEnergyWh(meterMissing ? null : meterSum);
        entity.setDeviationWh(deviationWh);
        entity.setDeviationRate(deviationRate);
        entity.setStatus(status);
        entity.setNote(note);
        reconciliationRepository.save(entity);

        return new ReconcileResult(stationAssetId, day, status, deviationWh, deviationRate, note);
    }

    /**
     * 全站对账（给定时 Job 调）：取所有 {@code PV_STATION} 资产逐个对账，
     * 收集偏差超阈值的 {@link ReconcileResult} 返回（落库已在 {@link #reconcileDay} 内完成）。
     *
     * @param day 对账日（UTC 日期）
     * @return 所有 WARN（超阈值）站点的对账结果
     */
    @Transactional
    public List<ReconcileResult> reconcileAll(LocalDate day) {
        List<Asset> stations = assetRepository.findByAssetType(AssetType.PV_STATION);
        List<ReconcileResult> warns = new ArrayList<>();
        for (Asset station : stations) {
            ReconcileResult result = reconcileDay(station.getId(), day);
            if (STATUS_WARN.equals(result.status())) {
                warns.add(result);
            }
        }
        return warns;
    }

    /** 是否有效数据：null 或 0 都视为缺失（coalesce(sum,0) 下无数据会落到 0）。 */
    private static boolean isMissing(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) == 0;
    }

    /** 偏差率转百分比字符串（保留 2 位，用于 note 展示）。 */
    private static String asPercent(BigDecimal rate) {
        return rate.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 偏差率阈值：优先取 system_config，读不到/非法回落默认 0.05。 */
    private BigDecimal loadDeviationThreshold() {
        Optional<SystemConfig> config =
                systemConfigRepository.findByConfigKeyAndDeletedFalse(CONFIG_KEY_DEVIATION_RATE);
        if (config.isEmpty() || config.get().getConfigValue() == null) {
            return DEFAULT_DEVIATION_RATE;
        }
        try {
            return new BigDecimal(config.get().getConfigValue().trim());
        } catch (NumberFormatException e) {
            log.warn("系统配置 {} 值非法({})，回落默认阈值 {}",
                    CONFIG_KEY_DEVIATION_RATE, config.get().getConfigValue(), DEFAULT_DEVIATION_RATE);
            return DEFAULT_DEVIATION_RATE;
        }
    }

    /**
     * 单站单日对账结果（reconcileDay 返回值）。
     *
     * @param stationAssetId 光伏站资产 id
     * @param day            对账日
     * @param status         OK / WARN / GAP
     * @param deviationWh    inverter − meter（GAP 时为 null）
     * @param deviationRate  deviation / meter（GAP 或 meter=0 时为 null）
     * @param note           说明（缺数据侧 / 实际偏差率等）
     */
    public record ReconcileResult(
            Long stationAssetId,
            LocalDate day,
            String status,
            BigDecimal deviationWh,
            BigDecimal deviationRate,
            String note) {
    }
}
