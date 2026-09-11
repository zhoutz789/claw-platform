package com.claw.server.domain.station;

import com.claw.server.domain.iot.BmsControlService;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.iot.TelemetryLatest;
import com.claw.server.domain.iot.TelemetryLatestRepository;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/**
 * 换电就绪判定（锂电池 BMS 对接方案 Phase D，对齐方案 §6）。
 *
 * <p>由 BMS 遥测驱动，取代"仅看 SOC"的旧判定：
 * <pre>
 *   soh &lt; 阈值                  → PENDING_RECOVERY（待回收）
 *   BMS 保护/故障                → FAULT
 *   temp &lt; 低温阈值              → NEEDS_HEATING（冷季下发 HEATER_ON 预热，预热后再判）
 *   temp &gt; 高温阈值              → FAULT（热季可下发 COOLING_ON，本切片先判不可用）
 *   soc &lt; 就绪阈值               → CHARGING
 *   否则                         → READY
 * </pre>
 * 阈值全部取自 {@code system_config}（缺失时回退方案默认值），<b>不硬编码</b>：
 * {@code SWAP_READY_SOC_MIN}(95) / {@code SWAP_TEMP_MIN}(5) / {@code SWAP_TEMP_MAX}(45) / {@code SWAP_SOH_MIN}(80)。
 *
 * <p>判定核心 {@link #decide} 为纯函数（不依赖 DB），便于单测与离线回放。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SwapReadinessService {

    public enum Decision {
        READY, CHARGING, NEEDS_HEATING, PENDING_RECOVERY, FAULT
    }

    public record ReadinessResult(
            Decision decision,
            String reason,
            /** 是否需要下发加热（冷季预热）。 */
            boolean shouldTriggerHeating,
            /** 是否需要下发水冷（热季）。 */
            boolean shouldTriggerCooling) {

        public ReadinessResult(Decision decision, String reason) {
            this(decision, reason, false, false);
        }
    }

    private final TelemetryLatestRepository telemetryLatestRepository;
    private final StationBatteryRepository stationBatteryRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final DeviceRepository deviceRepository;
    private final BmsControlService bmsControlService;

    /** 读取阈值 → 判定 → 必要时下发热管理指令 → 回写槽位状态。 */
    @Transactional
    public ReadinessResult evaluateAndApply(Long batteryId) {
        int socMin = cfgInt("SWAP_READY_SOC_MIN", 95);
        int tempMin = cfgInt("SWAP_TEMP_MIN", 5);
        int tempMax = cfgInt("SWAP_TEMP_MAX", 45);
        int sohMin = cfgInt("SWAP_SOH_MIN", 80);

        TelemetryLatest latest =
                telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(batteryId).orElse(null);

        ReadinessResult r = decide(latest, socMin, tempMin, tempMax, sohMin);

        if (r.shouldTriggerHeating() || r.shouldTriggerCooling()) {
            dispatchThermal(batteryId, r);
        }
        applySlotStatus(batteryId, r, latest);
        if (r.decision() == Decision.PENDING_RECOVERY) {
            // 待回收：方案要求"自动标待回收走既有回收域"，回收域接线见后续切片，此处先落审计日志。
            log.warn("[SWAP-READY] 电池 {} SOH 低于阈值，应转入回收域：{}", batteryId, r.reason());
        }
        log.info("[SWAP-READY] battery={} decision={} reason={}", batteryId, r.decision(), r.reason());
        return r;
    }

    /** 纯判定逻辑（无 DB 依赖）。 */
    public ReadinessResult decide(TelemetryLatest t, int socMin, int tempMin, int tempMax, int sohMin) {
        if (t == null) {
            return new ReadinessResult(Decision.CHARGING, "无 BMS 遥测");
        }
        if (hasProtection(t)) {
            return new ReadinessResult(Decision.FAULT, "BMS 保护/故障或放电禁用");
        }
        if (t.getSoh() != null && t.getSoh().compareTo(BigDecimal.valueOf(sohMin)) < 0) {
            return new ReadinessResult(Decision.PENDING_RECOVERY, "SOH 低于阈值(" + sohMin + ")，待回收");
        }
        BigDecimal cold = firstNonNull(t.getTempMin(), t.getTemp());
        if (cold != null && cold.compareTo(BigDecimal.valueOf(tempMin)) < 0) {
            return new ReadinessResult(Decision.NEEDS_HEATING, "低温(" + cold + "℃<" + tempMin + ")，需预热",
                    true, false);
        }
        BigDecimal hot = firstNonNull(t.getTempMax(), t.getTemp());
        if (hot != null && hot.compareTo(BigDecimal.valueOf(tempMax)) > 0) {
            return new ReadinessResult(Decision.FAULT, "高温(" + hot + "℃>" + tempMax + ")，需冷却", false, true);
        }
        if (t.getSoc() != null && t.getSoc().compareTo(BigDecimal.valueOf(socMin)) < 0) {
            return new ReadinessResult(Decision.CHARGING, "SOC 未达就绪阈值(" + socMin + "%)");
        }
        return new ReadinessResult(Decision.READY, "就绪（SOC/温度/SOH 均达标）");
    }

    /** 保护/故障判定：faults 有值 或 bms_state 为 fault/protect 或 放电被禁用。 */
    private boolean hasProtection(TelemetryLatest t) {
        if (t.getFaults() != null && !t.getFaults().isBlank()
                && !"null".equalsIgnoreCase(t.getFaults())) {
            return true;
        }
        if (t.getBmsState() != null) {
            String s = t.getBmsState().toLowerCase(Locale.ROOT);
            if ("fault".equals(s) || "protect".equals(s)) {
                return true;
            }
        }
        return Boolean.FALSE.equals(t.getDischargeEnable());
    }

    /** 下发热管理指令（加热/水冷），失败不影响判定结果。 */
    private void dispatchThermal(Long batteryId, ReadinessResult r) {
        String deviceNo = deviceRepository.findByAssetId(batteryId).stream()
                .filter(d -> "BATTERY_BMS".equals(d.getDeviceType()))
                .map(Device::getDeviceNo)
                .filter(s -> s != null && !s.isBlank())
                .findFirst().orElse(null);
        if (deviceNo == null) {
            log.warn("[SWAP-READY] 电池 {} 未找到 BATTERY_BMS 设备，跳过热管理下发", batteryId);
            return;
        }
        try {
            if (r.shouldTriggerHeating()) {
                bmsControlService.heaterOn(deviceNo);
            }
            if (r.shouldTriggerCooling()) {
                bmsControlService.coolingOn(deviceNo);
            }
        } catch (Exception e) {
            log.warn("[SWAP-READY] 热管理指令下发失败 battery={} deviceNo={}：{}", batteryId, deviceNo, e.getMessage());
        }
    }

    /** 回写换电站槽位状态（READY 满电可换，其余保持/回落到 CHARGING）。 */
    private void applySlotStatus(Long batteryId, ReadinessResult r, TelemetryLatest latest) {
        stationBatteryRepository.findByBatteryId(batteryId).ifPresent(slot -> {
            slot.setStatus(r.decision() == Decision.READY ? "READY" : "CHARGING");
            if (latest != null && latest.getSoc() != null) {
                slot.setSoc(latest.getSoc());
            }
            slot.setUpdatedAt(Instant.now());
            stationBatteryRepository.save(slot);
        });
    }

    private int cfgInt(String key, int fallback) {
        SystemConfig cfg = systemConfigRepository.findByConfigKeyAndDeletedFalse(key).orElse(null);
        if (cfg == null || cfg.getConfigValue() == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(cfg.getConfigValue().trim());
        } catch (NumberFormatException e) {
            log.warn("system_config.{} 值非法（{}），回退 {}", key, cfg.getConfigValue(), fallback);
            return fallback;
        }
    }

    private static BigDecimal firstNonNull(BigDecimal a, BigDecimal b) {
        return a != null ? a : b;
    }
}
