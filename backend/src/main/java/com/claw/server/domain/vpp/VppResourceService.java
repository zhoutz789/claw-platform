package com.claw.server.domain.vpp;

import com.claw.server.common.api.BizException;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.energy.EnergyDispatchService;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.iot.TelemetryLatest;
import com.claw.server.domain.iot.TelemetryLatestRepository;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import com.claw.server.domain.station.ChargeSession;
import com.claw.server.domain.station.ChargeSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 虚拟电厂资源注册与可调容量聚合（VPP 切片第一批，第二批改增量口径）。
 *
 * <p><b>容量口径（增量）：</b>对外承诺的「可调容量」按行业惯例是<b>增量</b>而非绝对出力：
 * <pre>
 *   up   （还能上调多少）= 最大技术出力 − 当前实际出力
 *   down （还能下调多少）= 当前实际出力 − 最小技术出力
 * </pre>
 * 因此 PV 满发时 {@code up = 0}（已经顶到上限，无上调空间），
 * PV 停机（有辐照但出力 0）时 {@code up = 技术上限 ≈ 额定}。
 *
 * <p><b>逐类型口径：</b>
 * <ul>
 *   <li>PV：技术上限 = 额定×(1−限发%)，受辐照度约束——辐照度为 0（夜间）时上限亦为 0，
 *       up/down 均 0（不能凭空发电，也没有可削减的出力）；</li>
 *   <li>ESS：放电限值 / 充电限值必须经 {@link EnergyDispatchService#powerLimit} 按
 *       DCL/CCL 钳制，不得直接用额定功率；缺遥测时该资源判为<b>不可用</b>，其功率
 *       <b>不计入</b>总容量（不是当 0 混入求和——混入会丢掉"有多少资源可信"的信息）；</li>
 *   <li>CHARGER：当前充电功率取活跃 {@link ChargeSession} 之和，无会话时回退遥测功率；
 *       up = 可增载，down = 可削减；</li>
 *   <li>DIESEL_GEN：仅当 ESS 最低 SOC 低于阈值（{@code VPP_DIESEL_START_SOC}，缺省 20%）
 *       时才计入——柴机的价值是替代兜底，SOC 充足时投入即浪费柴油。</li>
 * </ul>
 *
 * <p><b>功率符号约定：</b>ESS 侧沿用 BMS 契约「放电为正、充电为负」
 * （与 {@code TelemetryLatest.currentA} 一致），据此拆分当前充/放功率。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VppResourceService {

    /** 柴油机投入的 SOC 阈值配置键（低于该值才把柴机计入可调容量）。 */
    public static final String KEY_DIESEL_START_SOC = "VPP_DIESEL_START_SOC";
    public static final BigDecimal DEFAULT_DIESEL_START_SOC = new BigDecimal("20");

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final String STATUS_ONLINE = "ONLINE";

    // ===================== 模型 =====================

    /** 单资源可调容量明细（增量口径）。 */
    public record ResourceCapacity(
            Long resourceId,
            String resourceType,
            /** 还能上调的功率 W（最大技术出力 − 当前出力）。 */
            BigDecimal upW,
            /** 还能下调的功率 W（当前出力 − 最小技术出力）。 */
            BigDecimal downW,
            /** 遥测/数据是否可用；false 时 up/down 恒为 0 且不计入总量。 */
            boolean available,
            String note) {
    }

    /** 虚拟电厂可调容量（增量口径）。 */
    public record CapacityView(
            Long portfolioId,
            BigDecimal adjustableUpW,
            BigDecimal adjustableDownW,
            int onlineCount,
            int unavailableCount,
            List<ResourceCapacity> resources) {
    }

    // ===================== 注册 =====================

    /**
     * 注册资源。重复注册（asset_id 冲突）返回既有记录，不报错。
     *
     * @param assetId      资产 id
     * @param portfolioId  虚拟电厂 id
     * @param resourceType PV / ESS / CHARGER / DIESEL_GEN / CONTROLLABLE_LOAD
     * @return 资源实体
     */
    @Transactional
    public VppResource register(Long assetId, Long portfolioId, String resourceType) {
        if (assetId == null) {
            throw BizException.invalidParam("error.vpp.asset.id.missing");
        }
        if (portfolioId == null) {
            throw BizException.invalidParam("error.vpp.portfolio.id.missing");
        }
        if (!VppDispatchService.VALID_RESOURCE_TYPES.contains(resourceType)) {
            throw BizException.invalidParam("error.vpp.resource.type.invalid", String.valueOf(resourceType));
        }
        Optional<VppResource> existing = vppResourceRepository.findByAssetId(assetId);
        if (existing.isPresent()) {
            log.info("[VPP] 资产 {} 已注册为资源 {}，跳过重复注册", assetId, existing.get().getId());
            return existing.get();
        }
        if (!assetRepository.existsById(assetId)) {
            throw BizException.notFound("error.vpp.asset.not.found");
        }
        VppResource resource = VppResource.builder()
                .portfolioId(portfolioId)
                .assetId(assetId)
                .resourceType(resourceType)
                .status(STATUS_ONLINE)
                .build();
        return vppResourceRepository.save(resource);
    }

    // ===================== 容量聚合 =====================

    /**
     * 聚合某虚拟电厂的当前可调容量（增量口径）。
     *
     * @param portfolioId 虚拟电厂 id
     * @return 可调容量视图
     */
    public CapacityView capacity(Long portfolioId) {
        if (portfolioId == null) {
            throw BizException.invalidParam("error.vpp.portfolio.id.missing");
        }
        List<VppResource> online =
                vppResourceRepository.findByPortfolioIdAndStatus(portfolioId, STATUS_ONLINE);

        List<ResourceCapacity> details = new ArrayList<>();
        BigDecimal up = ZERO;
        BigDecimal down = ZERO;
        int unavailable = 0;

        // 柴油机是否投入取决于站内储能 SOC，先探一遍
        BigDecimal minEssSoc = null;
        for (VppResource r : online) {
            if (VppDispatchService.ESS.equals(r.getResourceType())) {
                TelemetryLatest t = latest(r.getAssetId());
                if (t != null && t.getSoc() != null
                        && (t.getPackVoltage() == null || t.getPackVoltage().signum() > 0)) {
                    BigDecimal soc = t.getSoc();
                    minEssSoc = minEssSoc == null ? soc : minEssSoc.min(soc);
                }
            }
        }
        BigDecimal dieselThreshold = null; // 惰性加载：仅在存在柴机资源时才读配置

        for (VppResource r : online) {
            TelemetryLatest t = latest(r.getAssetId());
            ResourceCapacity cap = capacityOf(r, t);
            if (VppDispatchService.DIESEL_GEN.equals(r.getResourceType()) && cap.available()) {
                if (dieselThreshold == null) {
                    dieselThreshold = decimalConfig(KEY_DIESEL_START_SOC, DEFAULT_DIESEL_START_SOC);
                }
                cap = gateDiesel(cap, minEssSoc, dieselThreshold);
            }
            if (!cap.available()) {
                unavailable++;
            } else {
                up = up.add(nz(cap.upW()));
                down = down.add(nz(cap.downW()));
            }
            details.add(cap);
        }

        return new CapacityView(portfolioId, up, down, online.size(), unavailable, details);
    }

    /** 单资源容量计算（不含柴机 SOC 门控）。 */
    private ResourceCapacity capacityOf(VppResource r, TelemetryLatest t) {
        String type = r.getResourceType();
        if (VppDispatchService.PV.equals(type)) {
            return pvCapacity(r, t);
        }
        if (VppDispatchService.ESS.equals(type)) {
            return essCapacity(r, t);
        }
        if (VppDispatchService.CHARGER.equals(type)) {
            return chargerCapacity(r, t);
        }
        if (VppDispatchService.CONTROLLABLE_LOAD.equals(type)) {
            return loadCapacity(r, t);
        }
        if (VppDispatchService.DIESEL_GEN.equals(type)) {
            return dieselCapacity(r, t);
        }
        return new ResourceCapacity(r.getId(), type, ZERO, ZERO, false, "未知资源类型 " + type);
    }

    /** 柴机 SOC 门控：SOC 未知或高于阈值 → 不可用。 */
    private ResourceCapacity gateDiesel(ResourceCapacity cap, BigDecimal minEssSoc, BigDecimal threshold) {
        if (minEssSoc == null) {
            return new ResourceCapacity(cap.resourceId(), cap.resourceType(), ZERO, ZERO, false,
                    "无有效储能 SOC，柴机不投入（无法判断是否需要兜底）");
        }
        if (minEssSoc.compareTo(threshold) >= 0) {
            return new ResourceCapacity(cap.resourceId(), cap.resourceType(), ZERO, ZERO, false,
                    "储能最低 SOC " + minEssSoc + "% ≥ 阈值 " + threshold + "%，柴机不投入");
        }
        return new ResourceCapacity(cap.resourceId(), cap.resourceType(), cap.upW(), cap.downW(), true,
                "储能最低 SOC " + minEssSoc + "% < 阈值 " + threshold + "%，柴机可投入");
    }

    /**
     * PV 增量容量：up = 技术上限 − 当前出力，down = 当前出力 − 技术下限。
     * 辐照度为 0（夜间）时技术上限亦为 0，故 up/down 均为 0。
     */
    private ResourceCapacity pvCapacity(VppResource r, TelemetryLatest t) {
        if (t == null) {
            return new ResourceCapacity(r.getId(), VppDispatchService.PV, ZERO, ZERO, false,
                    "缺少光伏遥测，不计入可调容量");
        }
        BigDecimal irradiance = t.getIrradiance();
        if (irradiance == null || irradiance.signum() <= 0) {
            return new ResourceCapacity(r.getId(), VppDispatchService.PV, ZERO, ZERO, true,
                    "辐照度为 0（夜间/无光），PV 无可调用容量");
        }
        BigDecimal actual = nz(t.getAcActivePowerW() != null ? t.getAcActivePowerW() : t.getMeterActivePowerW())
                .max(ZERO);
        BigDecimal rated = nz(r.getRatedPowerW());
        BigDecimal derate = nz(t.getDeratePercent()).max(ZERO).min(HUNDRED);
        // 技术上限：额定 ×(1−限发%)；未配额定则以当前出力为上限（无凭空发电空间）
        BigDecimal technicalMax = rated.signum() > 0
                ? rated.multiply(BigDecimal.ONE.subtract(derate.movePointLeft(2))).max(ZERO)
                : actual;
        BigDecimal technicalMin = nz(r.getAdjustableMinW()).max(ZERO);
        BigDecimal upW = technicalMax.subtract(actual).max(ZERO);
        BigDecimal downW = actual.subtract(technicalMin).max(ZERO);
        return new ResourceCapacity(r.getId(), VppDispatchService.PV, upW, downW, true,
                "辐照度 " + irradiance + " W/m²，当前出力 " + actual + "W / 技术上限 " + technicalMax
                        + "W：可上调 " + upW + "W、可下调 " + downW + "W");
    }

    /**
     * ESS 增量容量：up = 放电限值 − 当前放电功率，down = 充电限值 − 当前充电功率。
     * 两个限值均经 {@link EnergyDispatchService#powerLimit} 按 DCL/CCL 钳制。
     */
    private ResourceCapacity essCapacity(VppResource r, TelemetryLatest t) {
        if (t == null || t.getPackVoltage() == null
                || (t.getCcl() == null && t.getDcl() == null)) {
            return new ResourceCapacity(r.getId(), VppDispatchService.ESS, ZERO, ZERO, false,
                    "缺少 BMS 遥测（packVoltage/CCL/DCL），不计入可调容量");
        }
        BigDecimal max = r.getAdjustableMaxW() != null ? r.getAdjustableMaxW() : r.getRatedPowerW();
        BigDecimal dischargeLimit = t.getDcl() == null ? ZERO
                : EnergyDispatchService.powerLimit(t.getPackVoltage(), t.getDcl(), max);
        BigDecimal chargeLimit = t.getCcl() == null ? ZERO
                : EnergyDispatchService.powerLimit(t.getPackVoltage(), t.getCcl(), max);
        // 功率符号：放电正、充电负
        BigDecimal power = t.getPowerW();
        BigDecimal discharging = power == null ? ZERO : power.max(ZERO);
        BigDecimal charging = power == null ? ZERO : power.negate().max(ZERO);
        BigDecimal upW = dischargeLimit.subtract(discharging).max(ZERO);
        BigDecimal downW = chargeLimit.subtract(charging).max(ZERO);
        return new ResourceCapacity(r.getId(), VppDispatchService.ESS, upW, downW, true,
                "放电限值 " + dischargeLimit + "W（当前放电 " + discharging + "W）→ 可上调 " + upW
                        + "W；充电限值 " + chargeLimit + "W（当前充电 " + charging + "W）→ 可下调 " + downW + "W");
    }

    /** CHARGER 增量容量：当前充电功率取活跃会话之和（无会话回退遥测），up=可增载、down=可削减。 */
    private ResourceCapacity chargerCapacity(VppResource r, TelemetryLatest t) {
        String deviceNo = deviceRepository.findByAssetId(r.getAssetId()).stream()
                .map(Device::getDeviceNo)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse(null);

        BigDecimal current = null;
        int sessions = 0;
        if (deviceNo != null) {
            for (ChargeSession s : chargeSessionRepository.findByDeviceNoAndStatus(deviceNo, "ACTIVE")) {
                sessions++;
                current = nz(current).add(nz(s.getPeakPowerW()));
            }
        }
        if (current == null) {
            current = telemetryPower(t);
        }
        if (current == null) {
            return new ResourceCapacity(r.getId(), VppDispatchService.CHARGER, ZERO, ZERO, false,
                    "充电桩未绑定设备号且无遥测功率，无法统计当前充电功率");
        }
        BigDecimal v = current.max(ZERO);
        BigDecimal technicalMax = capOf(r);
        BigDecimal technicalMin = nz(r.getAdjustableMinW()).max(ZERO);
        BigDecimal upW = technicalMax.subtract(v).max(ZERO);
        BigDecimal downW = v.subtract(technicalMin).max(ZERO);
        return new ResourceCapacity(r.getId(), VppDispatchService.CHARGER, upW, downW, true,
                sessions + " 个活跃会话，当前充电 " + v + "W：可增载 " + upW + "W、可削减 " + downW + "W");
    }

    /** 可控负荷：up = 可增载，down = 可削减（当前用电功率）。 */
    private ResourceCapacity loadCapacity(VppResource r, TelemetryLatest t) {
        BigDecimal current = telemetryPower(t);
        if (current == null) {
            return new ResourceCapacity(r.getId(), VppDispatchService.CONTROLLABLE_LOAD, ZERO, ZERO, false,
                    "缺少功率遥测，不计入可调容量");
        }
        BigDecimal v = current.max(ZERO);
        BigDecimal technicalMax = capOf(r);
        BigDecimal technicalMin = nz(r.getAdjustableMinW()).max(ZERO);
        BigDecimal upW = technicalMax.subtract(v).max(ZERO);
        BigDecimal downW = v.subtract(technicalMin).max(ZERO);
        return new ResourceCapacity(r.getId(), VppDispatchService.CONTROLLABLE_LOAD, upW, downW, true,
                "当前负荷 " + v + "W：可增载 " + upW + "W、可削减 " + downW + "W");
    }

    /** 柴机：up = 可增出力，down = 可降载；是否投入由 gateDiesel 门控。 */
    private ResourceCapacity dieselCapacity(VppResource r, TelemetryLatest t) {
        BigDecimal technicalMax = capOf(r);
        if (technicalMax.signum() <= 0) {
            return new ResourceCapacity(r.getId(), VppDispatchService.DIESEL_GEN, ZERO, ZERO, false,
                    "未配置可调上限/额定功率");
        }
        BigDecimal current = nz(telemetryPower(t)).max(ZERO);
        BigDecimal upW = technicalMax.subtract(current).max(ZERO);
        BigDecimal downW = current.max(ZERO);
        return new ResourceCapacity(r.getId(), VppDispatchService.DIESEL_GEN, upW, downW, true,
                "容量 " + technicalMax + "W、当前出力 " + current + "W：可上调 " + upW
                        + "W、可降载 " + downW + "W（待 SOC 门控）");
    }

    /** 资源技术上限：adjustableMaxW 优先，缺失回退额定功率，再缺失回退 0。 */
    private static BigDecimal capOf(VppResource r) {
        BigDecimal max = r.getAdjustableMaxW() != null ? r.getAdjustableMaxW() : r.getRatedPowerW();
        return nz(max).max(ZERO);
    }

    /** 遥测功率：powerW → acActivePowerW → meterActivePowerW。 */
    private static BigDecimal telemetryPower(TelemetryLatest t) {
        if (t == null) {
            return null;
        }
        if (t.getPowerW() != null) {
            return t.getPowerW();
        }
        return t.getAcActivePowerW() != null ? t.getAcActivePowerW() : t.getMeterActivePowerW();
    }

    private TelemetryLatest latest(Long assetId) {
        return telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(assetId).orElse(null);
    }

    private BigDecimal decimalConfig(String key, BigDecimal fallback) {
        return systemConfigRepository.findByConfigKeyAndDeletedFalse(key)
                .map(SystemConfig::getConfigValue)
                .map(v -> v == null ? null : v.trim())
                .filter(v -> !v.isEmpty())
                .map(v -> {
                    try {
                        return new BigDecimal(v);
                    } catch (NumberFormatException e) {
                        log.warn("[VPP] system_config.{} 非法数值：{}，回退 {}", key, v, fallback);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .orElse(fallback);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private final VppResourceRepository vppResourceRepository;
    private final AssetRepository assetRepository;
    private final TelemetryLatestRepository telemetryLatestRepository;
    private final ChargeSessionRepository chargeSessionRepository;
    private final DeviceRepository deviceRepository;
    private final SystemConfigRepository systemConfigRepository;
}
