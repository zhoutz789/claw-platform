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
 * 虚拟电厂资源注册与可调容量聚合（VPP 切片第一批）。
 *
 * <p><b>容量口径铁律：</b>
 * <ul>
 *   <li>只统计 {@code status='ONLINE'} 的资源；</li>
 *   <li>PV 的可调上限受当前实际出力约束：{@code min(额定×(1-限发%), 当前交流有功)}，
 *       辐照度为 0（夜间）时为 0——不能凭空发电；</li>
 *   <li>ESS 必须经 {@link EnergyDispatchService#powerLimit} 按 CCL/DCL 钳制，
 *       不得直接用额定功率；缺遥测时该资源判为<b>不可用</b>，其功率<b>不计入</b>总容量
 *       （不是当 0 混入求和——混入会让"有多少资源可信"这一信息丢失）；</li>
 *   <li>CHARGER 按当前活跃 {@link ChargeSession} 的功率统计可削减量；</li>
 *   <li>DIESEL_GEN 仅当 ESS 最低 SOC 低于阈值（{@code VPP_DIESEL_START_SOC}，缺省 20%）
 *       时才计入——柴机的价值是替代兜底，SOC 充足时投入即浪费柴油。</li>
 * </ul>
 *
 * <p>{@code adjustableUpW / adjustableDownW} 均为<b>绝对功率</b>（W），不是相对当前的增量：
 * 上调 = 该资源可被要求提供的功率上限，下调 = 该资源可被要求削减的功率上限。
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

    /** 单资源可调容量明细。 */
    public record ResourceCapacity(
            Long resourceId,
            String resourceType,
            /** 可上调功率 W（绝对功率）。 */
            BigDecimal upW,
            /** 可下调功率 W（绝对功率）。 */
            BigDecimal downW,
            /** 遥测/数据是否可用；false 时 up/down 恒为 0 且不计入总量。 */
            boolean available,
            String note) {
    }

    /** 虚拟电厂可调容量。 */
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
     * 聚合某虚拟电厂的当前可调容量。
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
            ResourceCapacity cap = capacityOf(r);
            if (VppDispatchService.DIESEL_GEN.equals(r.getResourceType()) && cap.available()) {
                if (dieselThreshold == null) {
                    dieselThreshold = decimalConfig(KEY_DIESEL_START_SOC, DEFAULT_DIESEL_START_SOC);
                }
                ResourceCapacity gated = gateDiesel(cap, minEssSoc, dieselThreshold);
                cap = gated;
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
    private ResourceCapacity capacityOf(VppResource r) {
        String type = r.getResourceType();
        TelemetryLatest t = latest(r.getAssetId());
        if (VppDispatchService.PV.equals(type)) {
            return pvCapacity(r, t);
        }
        if (VppDispatchService.ESS.equals(type)) {
            return essCapacity(r, t);
        }
        if (VppDispatchService.CHARGER.equals(type)) {
            return chargerCapacity(r);
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

    /** PV：辐照度 0 → 0；否则 min(额定×(1-限发%), 当前交流有功)；可下调 = 可全削。 */
    private ResourceCapacity pvCapacity(VppResource r, TelemetryLatest t) {
        if (t == null) {
            return new ResourceCapacity(r.getId(), VppDispatchService.PV, ZERO, ZERO, false,
                    "缺少光伏遥测，不计入可调容量");
        }
        BigDecimal irradiance = t.getIrradiance();
        if (irradiance == null || irradiance.signum() <= 0) {
            return new ResourceCapacity(r.getId(), VppDispatchService.PV, ZERO, ZERO, true,
                    "辐照度为 0（夜间/无光），PV 无上调能力");
        }
        BigDecimal actual = t.getAcActivePowerW() != null ? t.getAcActivePowerW() : t.getMeterActivePowerW();
        BigDecimal rated = nz(r.getRatedPowerW());
        BigDecimal derate = nz(t.getDeratePercent()).max(ZERO).min(HUNDRED);
        if (rated.signum() == 0) {
            BigDecimal v = nz(actual);
            return new ResourceCapacity(r.getId(), VppDispatchService.PV, v, v, true,
                    "未配置额定功率，按当前出力 " + v + "W 计");
        }
        BigDecimal headroom = rated.multiply(BigDecimal.ONE.subtract(derate.movePointLeft(2))).max(ZERO);
        BigDecimal usable = actual == null ? headroom : headroom.min(actual.max(ZERO));
        return new ResourceCapacity(r.getId(), VppDispatchService.PV, usable, usable, true,
                "辐照度 " + irradiance + " W/m²，可调用 " + usable + "W（额定×限发余量 ∩ 当前出力）");
    }

    /** ESS：经 powerLimit 按 CCL/DCL 钳制；遥测缺失判不可用。 */
    private ResourceCapacity essCapacity(VppResource r, TelemetryLatest t) {
        if (t == null || t.getPackVoltage() == null
                || (t.getCcl() == null && t.getDcl() == null)) {
            return new ResourceCapacity(r.getId(), VppDispatchService.ESS, ZERO, ZERO, false,
                    "缺少 BMS 遥测（packVoltage/CCL/DCL），不计入可调容量");
        }
        BigDecimal max = r.getAdjustableMaxW() != null ? r.getAdjustableMaxW() : r.getRatedPowerW();
        BigDecimal upW = t.getDcl() == null ? ZERO
                : EnergyDispatchService.powerLimit(t.getPackVoltage(), t.getDcl(), max);
        BigDecimal downW = t.getCcl() == null ? ZERO
                : EnergyDispatchService.powerLimit(t.getPackVoltage(), t.getCcl(), max);
        return new ResourceCapacity(r.getId(), VppDispatchService.ESS, upW, downW, true,
                "放电上限 " + upW + "W（DCL），充电上限 " + downW + "W（CCL），均经 powerLimit 钳制");
    }

    /** CHARGER：可削减量 = 当前活跃充电会话功率之和。 */
    private ResourceCapacity chargerCapacity(VppResource r) {
        String deviceNo = deviceRepository.findByAssetId(r.getAssetId()).stream()
                .map(Device::getDeviceNo)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse(null);
        if (deviceNo == null) {
            return new ResourceCapacity(r.getId(), VppDispatchService.CHARGER, ZERO, ZERO, false,
                    "充电桩未绑定设备号，无法统计活跃会话");
        }
        BigDecimal curtail = ZERO;
        int sessions = 0;
        for (ChargeSession s : chargeSessionRepository.findByDeviceNoAndStatus(deviceNo, "ACTIVE")) {
            sessions++;
            curtail = curtail.add(nz(s.getPeakPowerW()));
        }
        if (sessions == 0) {
            return new ResourceCapacity(r.getId(), VppDispatchService.CHARGER, ZERO, ZERO, true,
                    "无活跃充电会话，可削减量为 0");
        }
        return new ResourceCapacity(r.getId(), VppDispatchService.CHARGER, ZERO, curtail, true,
                sessions + " 个活跃会话，可削减 " + curtail + "W");
    }

    /** 可控负荷：可削减量 = 当前用电功率。 */
    private ResourceCapacity loadCapacity(VppResource r, TelemetryLatest t) {
        if (t == null) {
            return new ResourceCapacity(r.getId(), VppDispatchService.CONTROLLABLE_LOAD, ZERO, ZERO, false,
                    "缺少遥测，不计入可调容量");
        }
        BigDecimal current = t.getPowerW() != null ? t.getPowerW() : t.getAcActivePowerW();
        if (current == null) {
            current = t.getMeterActivePowerW();
        }
        if (current == null) {
            return new ResourceCapacity(r.getId(), VppDispatchService.CONTROLLABLE_LOAD, ZERO, ZERO, false,
                    "遥测无功率字段，不计入可调容量");
        }
        BigDecimal v = current.max(ZERO);
        return new ResourceCapacity(r.getId(), VppDispatchService.CONTROLLABLE_LOAD, ZERO, v, true,
                "当前负荷 " + v + "W，可全削");
    }

    /** 柴机：上调 = 额定/可调上限；是否投入由 gateDiesel 门控。 */
    private ResourceCapacity dieselCapacity(VppResource r, TelemetryLatest t) {
        BigDecimal max = r.getAdjustableMaxW() != null ? r.getAdjustableMaxW() : r.getRatedPowerW();
        if (max == null || max.signum() <= 0) {
            return new ResourceCapacity(r.getId(), VppDispatchService.DIESEL_GEN, ZERO, ZERO, false,
                    "未配置可调上限/额定功率");
        }
        return new ResourceCapacity(r.getId(), VppDispatchService.DIESEL_GEN, max, ZERO, true,
                "额定出力 " + max + "W（待 SOC 门控）" + (t == null ? "（无遥测）" : ""));
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
