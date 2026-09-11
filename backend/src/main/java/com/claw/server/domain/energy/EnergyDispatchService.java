package com.claw.server.domain.energy;

import com.claw.server.common.enums.AssetUsageMode;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.iot.BmsControlService;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.iot.TelemetryLatest;
import com.claw.server.domain.iot.TelemetryLatestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 能源调度引擎 EMS（锂电池 BMS 对接方案 Phase D 收官，对齐方案 §6「能源调度引擎」）。
 *
 * <p>读取全 fleet 的 {@code soc / soh / ccl / dcl / temp / 组电压}，按电价与温度窗口计算
 * 充放电计划，并<b>闭环尊重 BMS 动态限值</b>——逆变器索要的功率不得超过 BMS 实时上报的
 * CCL/DCL（避免超限拉电流触发保护）。
 *
 * <p>调度优先级（安全优先于收益）：
 * <pre>
 *   温度 &lt; 下限  → HEAT（禁止充电，防析锂）
 *   温度 &gt; 上限  → COOL（降功率/停充放）
 *   充电窗口 且 SOC &lt; socMax → CHARGE（功率 = min(策略上限, CCL × 组电压)）
 *   放电窗口 且 SOC &gt; socMin → DISCHARGE（功率 = min(策略上限, DCL × 组电压)）
 *   否则       → IDLE
 * </pre>
 *
 * <p>候选容量：{@code ENERGY_STORAGE} 资产恒为 STORAGE 模式；换电 {@code BATTERY} 被借调时
 * 切换 usage mode 为 STORAGE（非类型变更），因此一次 {@code usageMode=STORAGE} 查询即得全部
 * 可调度储能容量。
 *
 * <p>决策核心（{@link #planFor} / {@link #powerLimit}）为纯函数，无 DB 依赖，便于单测与仿真。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnergyDispatchService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    // ===================== 模型 =====================

    public record BatterySnapshot(
            Long assetId,
            String usageMode,
            BigDecimal soc,
            BigDecimal soh,
            BigDecimal tempMin,
            BigDecimal tempMax,
            BigDecimal packVoltage,
            BigDecimal ccl,
            BigDecimal dcl) {
    }

    public record DispatchPolicy(
            BigDecimal socMax,
            BigDecimal socMin,
            BigDecimal tempMin,
            BigDecimal tempMax,
            /** 低电价 / 光伏富余 充电窗口。 */
            boolean chargeWindow,
            /** 高电价 / 负荷高峰 放电窗口。 */
            boolean dischargeWindow,
            BigDecimal maxPowerW) {
    }

    public enum Action { CHARGE, DISCHARGE, IDLE, HEAT, COOL }

    public record BatteryPlan(Long assetId, Action action, BigDecimal targetPowerW, String reason) {
    }

    public record DispatchPlan(List<BatteryPlan> plans) {
    }

    // ===================== 纯决策 =====================

    /** 单体调度决策（尊重 BMS 动态限值与温度安全窗）。 */
    public BatteryPlan planFor(BatterySnapshot b, DispatchPolicy p) {
        // 1) 温度安全优先：低温禁充（析锂），高温降功率
        if (b.tempMin() != null && b.tempMin().compareTo(p.tempMin()) < 0) {
            return new BatteryPlan(b.assetId(), Action.HEAT, ZERO,
                    "低温 " + b.tempMin() + "℃ < " + p.tempMin() + "℃，禁止充电（析锂风险），先加热");
        }
        if (b.tempMax() != null && b.tempMax().compareTo(p.tempMax()) > 0) {
            return new BatteryPlan(b.assetId(), Action.COOL, ZERO,
                    "高温 " + b.tempMax() + "℃ > " + p.tempMax() + "℃，先冷却");
        }
        if (b.soc() == null) {
            return new BatteryPlan(b.assetId(), Action.IDLE, ZERO, "无 SOC 遥测，跳过");
        }
        // 2) 充电窗口（低电价/光伏富余）：功率受 BMS CCL 约束
        if (p.chargeWindow() && b.soc().compareTo(p.socMax()) < 0) {
            BigDecimal power = powerLimit(b.packVoltage(), b.ccl(), p.maxPowerW());
            return new BatteryPlan(b.assetId(), Action.CHARGE, power,
                    "充电窗口：SOC " + b.soc() + "% < " + p.socMax() + "%，按 BMS CCL 限功率 " + power + "W");
        }
        // 3) 放电窗口（高电价/高峰）：功率受 BMS DCL 约束
        if (p.dischargeWindow() && b.soc().compareTo(p.socMin()) > 0) {
            BigDecimal power = powerLimit(b.packVoltage(), b.dcl(), p.maxPowerW());
            return new BatteryPlan(b.assetId(), Action.DISCHARGE, power,
                    "放电窗口：SOC " + b.soc() + "% > " + p.socMin() + "%，按 BMS DCL 限功率 " + power + "W");
        }
        return new BatteryPlan(b.assetId(), Action.IDLE, ZERO, "不在调度窗口或已达 SOC 边界");
    }

    /** 功率上限 = min(策略上限, 电流限值 × 组电压)；缺电压/电流限值时保守取策略上限。 */
    public static BigDecimal powerLimit(BigDecimal packVoltage, BigDecimal currentLimitA, BigDecimal maxPowerW) {
        BigDecimal policy = maxPowerW != null ? maxPowerW : ZERO;
        if (packVoltage == null || currentLimitA == null) {
            return policy.max(ZERO);
        }
        return packVoltage.multiply(currentLimitA).min(policy).max(ZERO);
    }

    public DispatchPlan planDispatch(List<BatterySnapshot> fleet, DispatchPolicy p) {
        return new DispatchPlan(fleet.stream().map(b -> planFor(b, p)).toList());
    }

    // ===================== 应用：加载 → 规划 → 下发 =====================

    /** 对指定资产执行调度（加载最新 BMS 遥测 → 规划 → 下发指令）。 */
    @Transactional
    public DispatchPlan dispatchFleet(List<Long> assetIds, DispatchPolicy p) {
        List<BatteryPlan> plans = new ArrayList<>();
        for (Long assetId : assetIds) {
            TelemetryLatest t =
                    telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(assetId).orElse(null);
            if (t == null) {
                plans.add(new BatteryPlan(assetId, Action.IDLE, ZERO, "无遥测，跳过"));
                continue;
            }
            BatterySnapshot snap = new BatterySnapshot(assetId, null, t.getSoc(), t.getSoh(),
                    t.getTempMin(), t.getTempMax(), t.getPackVoltage(), t.getCcl(), t.getDcl());
            BatteryPlan plan = planFor(snap, p);
            apply(assetId, plan);
            plans.add(plan);
        }
        log.info("[EMS] 调度完成 assets={} plans={}", assetIds.size(), plans.size());
        return new DispatchPlan(plans);
    }

    /**
     * 对全部可调度储能容量执行调度：{@code usageMode=STORAGE}
     * （含 ENERGY_STORAGE 资产 与 被借调的换电 BATTERY）。
     */
    @Transactional
    public DispatchPlan dispatchStorageCandidates(DispatchPolicy p) {
        List<Long> ids = assetRepository.findByUsageMode(AssetUsageMode.STORAGE).stream()
                .map(Asset::getId).toList();
        return dispatchFleet(ids, p);
    }

    private void apply(Long assetId, BatteryPlan plan) {
        if (plan.action() == Action.IDLE) {
            return;
        }
        String deviceNo = deviceRepository.findByAssetId(assetId).stream()
                .filter(d -> "BATTERY_BMS".equals(d.getDeviceType()))
                .map(Device::getDeviceNo)
                .filter(s -> s != null && !s.isBlank())
                .findFirst().orElse(null);
        if (deviceNo == null) {
            log.warn("[EMS] 资产 {} 未找到 BATTERY_BMS 设备，跳过下发（{}）", assetId, plan.action());
            return;
        }
        try {
            switch (plan.action()) {
                case CHARGE -> bmsControlService.enableCharge(deviceNo);
                case DISCHARGE -> bmsControlService.enableDischarge(deviceNo);
                case HEAT -> bmsControlService.heaterOn(deviceNo);
                case COOL -> bmsControlService.coolingOn(deviceNo);
                case IDLE -> { /* no-op */ }
            }
        } catch (Exception e) {
            log.warn("[EMS] 指令下发失败 asset={} deviceNo={} action={}：{}",
                    assetId, deviceNo, plan.action(), e.getMessage());
        }
    }

    private final TelemetryLatestRepository telemetryLatestRepository;
    private final DeviceRepository deviceRepository;
    private final AssetRepository assetRepository;
    private final BmsControlService bmsControlService;
}
