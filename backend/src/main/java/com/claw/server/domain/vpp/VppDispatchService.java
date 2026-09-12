package com.claw.server.domain.vpp;

import com.claw.server.common.api.BizException;
import com.claw.server.domain.energy.ElecTouService;
import com.claw.server.domain.energy.EnergyDispatchService;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceCommandService;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 虚拟电厂调度引擎（VPP 切片第一批）。
 *
 * <p><b>业务前提：</b>柬埔寨没有需求响应/辅助服务市场，参与电网调度不产生收益，
 * 故本 VPP 定位为「私域自用型」：聚合光伏/储能/充电桩/柴油机组做站内自用优化
 * （柴油替代 + 需量管理 + 自发自用最大化）。对外电网/DR 接口只留抽象，本期不实现。
 *
 * <p><b>调度优先级链（安全优先，严格按序降级）：</b>
 * <pre>
 *   1. 光伏优先自用（irradiance &gt; 0 且本地负荷 &gt; 0）
 *   2. 余电充储能（尊重 ESS 的 CCL 与低温禁充：temp &lt; 充电温度下限 时禁充）
 *   3. 仍余 → 充充电桩（可调度负荷，放开削减）
 *   4. 仍余 → 上网 / 限发（无 TOU 电价信号时一律限发 DERATE_PV）
 *   5. 不足 → 储能放电（尊重 DCL + powerLimit 钳制）
 *   6. 仍不足 → 市电 / 柴油（本期只输出建议，不真控柴机）
 * </pre>
 *
 * <p><b>决策核心 {@link #dispatchPlan(List, Instant)} 为静态方法、无 DB 依赖</b>，
 * 输入资源快照输出指令草稿，便于单测与仿真。
 *
 * <p><b>安全底线——影子模式：</b>{@link #issueOrders} 读 system_config 的
 * {@code VPP_SHADOW_MODE}（缺省 {@code true}）。为 true 时只生成建议并落
 * {@code shadow=true} 的指令行，<b>绝不调用任何下发通道</b>；为 false 才经
 * {@link DeviceCommandService} 真正下发。
 *
 * <p><b>功率口径铁律：</b>储能侧一律经
 * {@link EnergyDispatchService#powerLimit(BigDecimal, BigDecimal, BigDecimal)} 钳制，
 * 绝不绕过 BMS 动态限值（CCL/DCL）。注意该方法在「电压或电流限值缺失」时会回退到策略上限，
 * 对 VPP 聚合而言等于凭空给容量，因此本服务在调用<b>之前</b>先判定遥测可用性，
 * 缺失即判该资源不可用、其功率不计入容量（不是当 0 混入求和）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VppDispatchService {

    // ===================== 常量与配置键 =====================

    public static final String PV = "PV";
    public static final String ESS = "ESS";
    public static final String CHARGER = "CHARGER";
    public static final String DIESEL_GEN = "DIESEL_GEN";
    public static final String CONTROLLABLE_LOAD = "CONTROLLABLE_LOAD";

    public static final String CMD_DERATE_PV = "DERATE_PV";
    public static final String CMD_CHARGE_ESS = "CHARGE_ESS";
    public static final String CMD_DISCHARGE_ESS = "DISCHARGE_ESS";
    /** 削减充电桩功率，targetW = 相对当前功率的<b>削减量</b>。 */
    public static final String CMD_CURTAIL_CHARGER = "CURTAIL_CHARGER";
    /** 设定充电桩功率，targetW = <b>绝对功率</b> W（余电升功率场景用，避免用削减量反向表达）。 */
    public static final String CMD_SET_CHARGER_POWER = "SET_CHARGER_POWER";
    public static final String CMD_START_GEN = "START_GEN";

    public static final Set<String> VALID_RESOURCE_TYPES = Set.of(PV, ESS, CHARGER, DIESEL_GEN, CONTROLLABLE_LOAD);
    public static final Set<String> VALID_COMMAND_TYPES = Set.of(CMD_DERATE_PV, CMD_CHARGE_ESS,
            CMD_DISCHARGE_ESS, CMD_CURTAIL_CHARGER, CMD_SET_CHARGER_POWER, CMD_START_GEN);

    public static final String STATUS_ONLINE = "ONLINE";

    /** 影子模式开关。缺省 true（宁可不控，不可误控）。 */
    public static final String KEY_SHADOW_MODE = "VPP_SHADOW_MODE";
    /** ESS 充电温度下限 ℃（低温禁充防析锂，对齐 EnergyDispatchService 单测口径 5℃）。 */
    public static final String KEY_CHARGE_TEMP_MIN = "VPP_ESS_CHARGE_TEMP_MIN";
    /** ESS 放电 SOC 下限 %。 */
    public static final String KEY_ESS_SOC_MIN = "VPP_ESS_SOC_MIN";
    /**
     * 光伏余电是否允许上网。
     *
     * <p><b>仅当确认存在净计量 / 余电回购时才可开启，否则应保守弃光</b>——
     * 不存在收益模型时假设收益会导致错误的调度决策。TOU 电价接入后由价格信号驱动，
     * 本开关作为兜底保留（取不到电价时以本开关为准）。
     */
    public static final String KEY_EXPORT_ALLOWED = "VPP_EXPORT_ALLOWED";
    /** 并网点需量目标 W（需量管理削峰阈值）；留空表示未配置，需量管理不生效。 */
    public static final String KEY_DEMAND_TARGET_W = "VPP_DEMAND_TARGET_W";

    public static final BigDecimal DEFAULT_CHARGE_TEMP_MIN_C = new BigDecimal("5");
    public static final BigDecimal DEFAULT_ESS_SOC_MIN = new BigDecimal("10");
    public static final boolean DEFAULT_EXPORT_ALLOWED = false;

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    // ===================== 模型 =====================

    /** 资源快照（纯数据，构造后不可变）。 */
    public record ResourceSnapshot(
            Long resourceId,
            /** PV / ESS / CHARGER / DIESEL_GEN / CONTROLLABLE_LOAD。 */
            String resourceType,
            BigDecimal ratedPowerW,
            BigDecimal adjustableMinW,
            BigDecimal adjustableMaxW,
            /** 当前实际功率 W：PV=交流有功；ESS=当前充放功率；负荷=当前用电功率。 */
            BigDecimal currentPowerW,
            /** 逆变器限发百分比 %（0–100）。 */
            BigDecimal deratePercent,
            /** 瞬时辐照度 W/m²（PV）。 */
            BigDecimal irradiance,
            /** SOC %（ESS）。 */
            BigDecimal soc,
            /** 电芯最低温 ℃（ESS）。 */
            BigDecimal tempMin,
            /** BMS 实时充电电流限值 A。 */
            BigDecimal ccl,
            /** BMS 实时放电电流限值 A。 */
            BigDecimal dcl,
            /** 组电压 V。 */
            BigDecimal packVoltage,
            /** 遥测是否可用（不可用的资源不计入可调容量）。 */
            boolean telemetryAvailable) {

        public boolean isPv() {
            return PV.equals(resourceType);
        }

        public boolean isEss() {
            return ESS.equals(resourceType);
        }

        public boolean isCharger() {
            return CHARGER.equals(resourceType);
        }

        public boolean isDieselGen() {
            return DIESEL_GEN.equals(resourceType);
        }

        /** 参与本地负荷统计（可削减、可时移的需求侧资源）。 */
        public boolean isLoad() {
            return CHARGER.equals(resourceType) || CONTROLLABLE_LOAD.equals(resourceType);
        }
    }

    /** 调度选项（温度/SOC 阈值与上网策略，可由 system_config 覆盖）。 */
    public record VppDispatchOptions(
            BigDecimal chargeTempMinC,
            BigDecimal essSocMinPercent,
            boolean exportAllowed) {

        public static VppDispatchOptions defaults() {
            return new VppDispatchOptions(DEFAULT_CHARGE_TEMP_MIN_C, DEFAULT_ESS_SOC_MIN, DEFAULT_EXPORT_ALLOWED);
        }
    }

    /**
     * 调度决策的外部上下文（TOU 价格信号 + 并网点需量）。
     *
     * <p>任一价格字段取不到时保持 {@code null}——调用方据此回落保守策略，
     * <b>严禁用默认值冒充真实价格</b>。
     */
    public record DispatchContext(
            /** PEAK / FLAT / VALLEY / UNKNOWN。 */
            String slotCode,
            /** 电度电价 $/kWh；null = 无 TOU 数据。 */
            BigDecimal energyPrice,
            /** 需量电价 $/kW；null = 无数据或未配置。 */
            BigDecimal demandPrice,
            /** 峰/谷判断参考基准（全时段加权平均电价）；null = 无法判断。 */
            BigDecimal referencePrice,
            /** 并网点当前实测需量 W（来自遥测 demand_w）；null = 无数据。 */
            BigDecimal gridDemandW,
            /** 需量目标 W（VPP_DEMAND_TARGET_W）；null = 未配置，需量管理不生效。 */
            BigDecimal demandTargetW) {

        /** 无价格、无需量数据的上下文（保守决策）。 */
        public static DispatchContext unknown() {
            return new DispatchContext(ElecTouService.UNKNOWN, null, null, null, null, null);
        }

        /** 价格信号可用（当前电价与参考基准都能取到）。 */
        public boolean priceDriven() {
            return energyPrice != null && referencePrice != null;
        }

        /** 当前电价 ≥ 参考基准 → 高价（峰）时段；价格不可用时不成立。 */
        public boolean peakish() {
            return priceDriven() && energyPrice.compareTo(referencePrice) >= 0;
        }

        /** 并网点需量已超目标（需量管理触发条件）；缺任一输入则不成立。 */
        public boolean demandExceeded() {
            return gridDemandW != null && demandTargetW != null
                    && gridDemandW.compareTo(demandTargetW) > 0;
        }
    }

    /** 指令草稿（尚未落库）。 */
    public record CommandDraft(Long resourceId, String commandType, BigDecimal targetW, String reason) {
    }

    /** 调度结果（纯函数输出，含能量平衡明细，便于审计与仿真回放）。 */
    public record DispatchOutcome(
            Instant at,
            List<CommandDraft> commands,
            /** 光伏可调用上限 W（夜间为 0）。 */
            BigDecimal pvAvailableW,
            /** 本地负荷 W。 */
            BigDecimal localLoadW,
            /** 光伏自用 W。 */
            BigDecimal pvSelfUseW,
            /** 自用后余电 W。 */
            BigDecimal surplusW,
            /** 自用后缺口 W。 */
            BigDecimal deficitW,
            /** 限发量 W（无上网激励时按此削减光伏出力）。 */
            BigDecimal curtailedPvW,
            /** 最终仍需市电/柴油补充的缺口 W。 */
            BigDecimal gridImportW,
            List<String> notes) {
    }

    /** 下发目标（落库指令的输入）。 */
    public record CommandTarget(
            Long resourceId,
            String commandType,
            BigDecimal targetW,
            Instant startAt,
            Instant endAt,
            String reason) {
    }

    // ===================== 纯决策 =====================

    /**
     * 调度决策（默认选项）。
     *
     * @param resources 资源快照；null 或空列表返回空计划，不抛异常
     * @param now       决策时刻
     * @return 调度结果
     */
    public static DispatchOutcome dispatchPlan(List<ResourceSnapshot> resources, Instant now) {
        return dispatchPlan(resources, now, VppDispatchOptions.defaults(), DispatchContext.unknown());
    }

    /**
     * 调度决策（显式选项，无价格上下文 → 保守决策）。
     *
     * @param resources 资源快照
     * @param now       决策时刻
     * @param opt       阈值与上网策略
     * @return 调度结果
     */
    public static DispatchOutcome dispatchPlan(List<ResourceSnapshot> resources, Instant now,
                                               VppDispatchOptions opt) {
        return dispatchPlan(resources, now, opt, DispatchContext.unknown());
    }

    /**
     * 调度决策（完整入参：显式选项 + 价格/需量上下文）。严格按优先级链降级，见类注释。
     *
     * <p><b>价格驱动的两处决策：</b>
     * <ol>
     *   <li>余电「上网 vs 限发」：{@code exportAllowed} 为 false 一律限发；为 true 但无电价
     *       → 回落限发；有电价时仅在高价（≥ 参考基准）时段上网，低价时段限发。</li>
     *   <li>缺口「ESS 放电 vs 市电/柴机」：无电价 → 回落「优先 ESS 放电」；
     *       有电价且为低价时段且未超需量 → 市电更便宜，ESS 保留至峰段；
     *       高价时段或需量超标 → 优先 ESS 放电（削峰）。</li>
     * </ol>
     *
     * @param resources 资源快照
     * @param now       决策时刻
     * @param opt       阈值与上网策略
     * @param ctx       价格与需量上下文；null 视为 unknown（保守）
     * @return 调度结果
     */
    public static DispatchOutcome dispatchPlan(List<ResourceSnapshot> resources, Instant now,
                                               VppDispatchOptions opt, DispatchContext ctx) {
        List<ResourceSnapshot> list = resources == null ? List.of()
                : resources.stream().filter(Objects::nonNull).toList();
        VppDispatchOptions options = opt == null ? VppDispatchOptions.defaults() : opt;
        DispatchContext context = ctx == null ? DispatchContext.unknown() : ctx;
        Instant decidedAt = now == null ? Instant.EPOCH : now;

        List<CommandDraft> commands = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (list.isEmpty()) {
            notes.add("无可用资源，返回空调度计划");
            return new DispatchOutcome(decidedAt, commands, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, notes);
        }

        // 1) 光伏可调用上限 + 本地负荷
        List<ResourceSnapshot> pvList = list.stream().filter(ResourceSnapshot::isPv).toList();
        BigDecimal pvAvail = ZERO;
        for (ResourceSnapshot r : pvList) {
            pvAvail = pvAvail.add(pvAvailableW(r));
        }
        BigDecimal localLoad = ZERO;
        for (ResourceSnapshot r : list) {
            if (r.isLoad()) {
                localLoad = localLoad.add(nz(r.currentPowerW()).max(ZERO));
            }
        }
        BigDecimal pvSelfUse = pvAvail.min(localLoad);

        BigDecimal surplus = pvAvail.subtract(localLoad).max(ZERO);
        BigDecimal deficit = localLoad.subtract(pvAvail).max(ZERO);
        BigDecimal pvAllowed = pvAvail;

        if (surplus.signum() > 0) {
            // 2) 余电充储能：受 CCL / powerLimit 钳制，低温禁充
            BigDecimal remaining = surplus;
            for (ResourceSnapshot r : list) {
                if (!r.isEss() || remaining.signum() <= 0) {
                    continue;
                }
                BigDecimal limit = essChargeW(r, options);
                if (limit.signum() <= 0) {
                    notes.add("资源 " + r.resourceId() + " 储能不可充电（"
                            + essBlockReason(r, options) + "），跳过余电消纳");
                    continue;
                }
                BigDecimal take = limit.min(remaining);
                commands.add(new CommandDraft(r.resourceId(), CMD_CHARGE_ESS, take,
                        "光伏余电充电 " + take + "W，受 BMS CCL 限值 " + limit + "W 约束"));
                remaining = remaining.subtract(take);
            }
            // 3) 仍余 → 充充电桩（可调度负荷）：用 SET_CHARGER_POWER 直接给绝对功率设定值
            for (ResourceSnapshot r : list) {
                if (!r.isCharger() || remaining.signum() <= 0) {
                    continue;
                }
                BigDecimal headroom = capOf(r).subtract(nz(r.currentPowerW())).max(ZERO);
                BigDecimal boost = headroom.min(remaining);
                BigDecimal target = nz(r.currentPowerW()).add(boost);
                commands.add(new CommandDraft(r.resourceId(), CMD_SET_CHARGER_POWER, target,
                        "光伏余电提升充电功率至 " + target + "W（较当前 +" + boost + "W）"));
                remaining = remaining.subtract(boost);
            }
            // 4) 仍余 → 上网 or 限发（价格驱动；兜底保守 = 限发）
            if (remaining.signum() > 0) {
                if (!options.exportAllowed()) {
                    pvAllowed = pvAvail.subtract(remaining).max(ZERO);
                    notes.add("VPP_EXPORT_ALLOWED=false（保守兜底），余电 " + remaining
                            + "W 转为光伏限发，出力上限压到 " + pvAllowed + "W");
                } else if (!context.priceDriven()) {
                    pvAllowed = pvAvail.subtract(remaining).max(ZERO);
                    notes.add("无 TOU 电价数据，回落保守策略：余电 " + remaining
                            + "W 不上网，转光伏限发，出力上限压到 " + pvAllowed + "W");
                    log.debug("[VPP] 无 TOU 电价数据（slot={} price={} ref={}），回落保守策略：余电限发",
                            context.slotCode(), context.energyPrice(), context.referencePrice());
                } else if (context.peakish()) {
                    notes.add("高价时段（" + context.slotCode() + " " + context.energyPrice()
                            + " ≥ 参考 " + context.referencePrice() + " $/kWh），余电 " + remaining + "W 上网");
                } else {
                    pvAllowed = pvAvail.subtract(remaining).max(ZERO);
                    notes.add("低价时段（" + context.slotCode() + " " + context.energyPrice()
                            + " < 参考 " + context.referencePrice() + " $/kWh），上网价值低，余电 "
                            + remaining + "W 转光伏限发，出力上限压到 " + pvAllowed + "W");
                }
            }
        } else if (deficit.signum() > 0) {
            // 5/6) 不足 → 储能放电 or 市电/柴油（价格驱动 + 需量削峰）
            boolean demandShaving = context.demandExceeded();
            boolean preferEss;
            if (demandShaving) {
                preferEss = true;
                notes.add("并网点需量 " + context.gridDemandW() + "W 超目标 " + context.demandTargetW()
                        + "W（需量电价 " + context.demandPrice() + " $/kW），优先储能放电削峰");
            } else if (!context.priceDriven()) {
                preferEss = true;
                notes.add("无 TOU 电价数据，回落保守策略：优先储能放电填补缺口");
                log.debug("[VPP] 无 TOU 电价数据（slot={} price={} ref={}），回落保守策略：优先 ESS 放电",
                        context.slotCode(), context.energyPrice(), context.referencePrice());
            } else if (context.peakish()) {
                preferEss = true;
                notes.add("高价时段（" + context.slotCode() + " " + context.energyPrice()
                        + " ≥ 参考 " + context.referencePrice() + " $/kWh），优先储能放电替代高价市电");
            } else {
                preferEss = false;
                notes.add("低价时段（" + context.slotCode() + " " + context.energyPrice()
                        + " < 参考 " + context.referencePrice() + " $/kWh）且未超需量，"
                        + "缺口由市电承担，储能保留至峰段");
            }

            BigDecimal remaining = deficit;
            if (preferEss) {
                for (ResourceSnapshot r : list) {
                    if (!r.isEss() || remaining.signum() <= 0) {
                        continue;
                    }
                    BigDecimal limit = essDischargeW(r, options);
                    if (limit.signum() <= 0) {
                        notes.add("资源 " + r.resourceId() + " 储能不可放电（"
                                + essBlockReason(r, options) + "），跳过缺口填补");
                        continue;
                    }
                    BigDecimal take = limit.min(remaining);
                    commands.add(new CommandDraft(r.resourceId(), CMD_DISCHARGE_ESS, take,
                            "填补负荷缺口 " + take + "W，受 BMS DCL 限值 " + limit + "W 约束"));
                    remaining = remaining.subtract(take);
                }
            }
            if (remaining.signum() > 0) {
                boolean hasGen = false;
                if (preferEss) {
                    for (ResourceSnapshot r : list) {
                        if (!r.isDieselGen() || remaining.signum() <= 0) {
                            continue;
                        }
                        hasGen = true;
                        BigDecimal take = capOf(r).min(remaining);
                        commands.add(new CommandDraft(r.resourceId(), CMD_START_GEN, take,
                                "缺口 " + take + "W 建议投入柴油机组（本期仅建议，不实际控制）"));
                        remaining = remaining.subtract(take);
                    }
                }
                if (!hasGen) {
                    notes.add("缺口 " + remaining + "W 由市电承担"
                            + (preferEss ? "（无可调度柴机资源）" : "（低价时段，市电更经济）"));
                }
            }
        }

        // 光伏出力指令：夜间（可调用上限 0）不产生任何指令
        BigDecimal curtailed = ZERO;
        if (pvAvail.signum() > 0) {
            for (ResourceSnapshot r : pvList) {
                BigDecimal avail = pvAvailableW(r);
                if (avail.signum() <= 0) {
                    continue;
                }
                BigDecimal target = avail;
                if (pvAllowed.compareTo(pvAvail) < 0) {
                    target = avail.multiply(pvAllowed).divide(pvAvail, 2, RoundingMode.HALF_UP).max(ZERO);
                }
                curtailed = curtailed.add(avail.subtract(target).max(ZERO));
                commands.add(new CommandDraft(r.resourceId(), CMD_DERATE_PV, target,
                        target.compareTo(avail) < 0
                                ? "光伏限发至 " + target + "W（可发 " + avail + "W，无消纳空间/无上网激励）"
                                : "光伏全额自用 " + target + "W"));
            }
        } else {
            notes.add("光伏不可调用（夜间或辐照度为 0），不产生光伏出力指令");
        }

        BigDecimal gridImport = localLoad.subtract(pvSelfUse).max(ZERO);
        return new DispatchOutcome(decidedAt, commands, pvAvail, localLoad, pvSelfUse,
                surplus, deficit, curtailed, gridImport, notes);
    }

    /** 光伏可调用上限：辐照度为 0（夜间）直接 0；否则 min(额定×(1-限发%), 当前交流有功)。 */
    public static BigDecimal pvAvailableW(ResourceSnapshot r) {
        if (r == null || !r.isPv()) {
            return ZERO;
        }
        BigDecimal irradiance = r.irradiance();
        if (irradiance == null || irradiance.signum() <= 0) {
            return ZERO;
        }
        BigDecimal rated = nz(r.ratedPowerW()).max(ZERO);
        BigDecimal actual = r.currentPowerW() == null ? null : r.currentPowerW().max(ZERO);
        if (rated.signum() == 0) {
            return nz(actual);
        }
        // 限发余量：额定 × (1 - derate%)
        BigDecimal derate = nz(r.deratePercent()).max(ZERO).min(HUNDRED);
        BigDecimal headroom = rated.multiply(BigDecimal.ONE.subtract(derate.movePointLeft(2))).max(ZERO);
        if (actual == null) {
            return headroom;
        }
        return headroom.min(actual).max(ZERO);
    }

    /**
     * 储能可充电功率：经 {@link EnergyDispatchService#powerLimit} 按 CCL 钳制。
     * 低温（&lt; 充电温度下限）禁充防析锂，返回 0。
     */
    public static BigDecimal essChargeW(ResourceSnapshot r, VppDispatchOptions opt) {
        if (!essTelemetryUsable(r)) {
            return ZERO;
        }
        VppDispatchOptions o = opt == null ? VppDispatchOptions.defaults() : opt;
        BigDecimal tempMin = r.tempMin();
        if (tempMin != null && tempMin.compareTo(o.chargeTempMinC()) < 0) {
            return ZERO;
        }
        if (r.ccl() == null) {
            return ZERO;
        }
        return EnergyDispatchService.powerLimit(r.packVoltage(), r.ccl(), capOf(r));
    }

    /**
     * 储能可放电功率：经 {@link EnergyDispatchService#powerLimit} 按 DCL 钳制。
     * SOC 低于下限时不出力。
     */
    public static BigDecimal essDischargeW(ResourceSnapshot r, VppDispatchOptions opt) {
        if (!essTelemetryUsable(r)) {
            return ZERO;
        }
        VppDispatchOptions o = opt == null ? VppDispatchOptions.defaults() : opt;
        BigDecimal soc = r.soc();
        if (soc != null && soc.compareTo(o.essSocMinPercent()) <= 0) {
            return ZERO;
        }
        if (r.dcl() == null) {
            return ZERO;
        }
        return EnergyDispatchService.powerLimit(r.packVoltage(), r.dcl(), capOf(r));
    }

    /**
     * 储能遥测是否可用于聚合：组电压必须有值，CCL/DCL 至少有一个。
     *
     * <p>为什么在调用 powerLimit 之前先判：powerLimit 在电压或电流限值缺失时回退到
     * 策略上限（额定功率），对聚合意味着「没有遥测也能给满容量」，属于凭空容量，
     * 与安全底线冲突，故此处先行拦截。
     */
    public static boolean essTelemetryUsable(ResourceSnapshot r) {
        return r != null && r.isEss() && r.telemetryAvailable()
                && r.packVoltage() != null
                && (r.ccl() != null || r.dcl() != null);
    }

    /** 储能不可充放的原因（用于 note 与排障）。 */
    public static String essBlockReason(ResourceSnapshot r, VppDispatchOptions opt) {
        if (r == null) {
            return "资源为空";
        }
        if (!r.telemetryAvailable() || r.packVoltage() == null) {
            return "缺少 BMS 遥测（packVoltage/CCL/DCL）";
        }
        VppDispatchOptions o = opt == null ? VppDispatchOptions.defaults() : opt;
        if (r.tempMin() != null && r.tempMin().compareTo(o.chargeTempMinC()) < 0) {
            return "低温 " + r.tempMin() + "℃ < " + o.chargeTempMinC() + "℃，禁止充电（析锂风险）";
        }
        if (r.soc() != null && r.soc().compareTo(o.essSocMinPercent()) <= 0) {
            return "SOC " + r.soc() + "% 已达放电下限 " + o.essSocMinPercent() + "%";
        }
        return "BMS 电流限值缺失（CCL/DCL 均为空）";
    }

    // ===================== 应用：加载 → 规划 → 落库/下发 =====================

    private final VppResourceRepository vppResourceRepository;
    private final VppPortfolioRepository vppPortfolioRepository;
    private final VppDispatchOrderRepository vppDispatchOrderRepository;
    private final TelemetryLatestRepository telemetryLatestRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceCommandService deviceCommandService;
    private final SystemConfigRepository systemConfigRepository;
    private final ElecTouService elecTouService;

    /** 从配置解析选项（缺省值兜底，配置缺失或非法不抛异常）。 */
    public VppDispatchOptions resolveOptions() {
        return new VppDispatchOptions(
                decimalConfig(KEY_CHARGE_TEMP_MIN, DEFAULT_CHARGE_TEMP_MIN_C),
                decimalConfig(KEY_ESS_SOC_MIN, DEFAULT_ESS_SOC_MIN),
                boolConfig(KEY_EXPORT_ALLOWED, DEFAULT_EXPORT_ALLOWED));
    }

    /** 影子模式开关：读 VPP_SHADOW_MODE，缺省 true。 */
    public boolean shadowMode() {
        return boolConfig(KEY_SHADOW_MODE, true);
    }

    /**
     * 组装价格/需量上下文。取不到电价的字段保持 {@code null}——
     * 下游 {@link #dispatchPlan} 据此回落保守策略，绝不编造默认值。
     */
    public DispatchContext resolveContext(Long portfolioId, Instant now) {
        Instant at = now == null ? Instant.now() : now;
        ElecTouService.TouSlot slot = elecTouService.slotAt(at);
        BigDecimal price = slot.known() ? slot.energyPrice() : null;
        BigDecimal demandPrice = slot.known() ? slot.demandPrice() : null;
        BigDecimal reference = elecTouService.referencePrice();
        if (price == null) {
            log.debug("[VPP] 无 TOU 电价数据（slot={}），价格相关决策回落保守策略", slot.slotCode());
        }
        return new DispatchContext(slot.slotCode(), price, demandPrice, reference,
                gridDemandW(portfolioId), demandTargetW());
    }

    /**
     * 并网点当前实测需量 W：取站内资源遥测 {@code demand_w} 的最大值。
     * 无该字段（电表未上报需量）时返回 null → 需量管理不生效。
     */
    private BigDecimal gridDemandW(Long portfolioId) {
        BigDecimal max = null;
        for (VppResource r : vppResourceRepository
                .findByPortfolioIdAndStatus(portfolioId, STATUS_ONLINE)) {
            TelemetryLatest t = telemetryLatestRepository
                    .findTopByAssetIdOrderByReportedAtDescIdDesc(r.getAssetId()).orElse(null);
            BigDecimal d = t == null ? null : t.getDemandW();
            if (d != null && (max == null || d.compareTo(max) > 0)) {
                max = d;
            }
        }
        return max;
    }

    /** 需量目标 W（VPP_DEMAND_TARGET_W）；未配置返回 null。 */
    private BigDecimal demandTargetW() {
        return systemConfigRepository.findByConfigKeyAndDeletedFalse(KEY_DEMAND_TARGET_W)
                .map(SystemConfig::getConfigValue)
                .map(v -> v == null ? null : v.trim())
                .filter(v -> !v.isEmpty())
                .map(v -> {
                    try {
                        return new BigDecimal(v);
                    } catch (NumberFormatException e) {
                        log.warn("[VPP] system_config.{} 非法数值：{}，需量管理不生效", KEY_DEMAND_TARGET_W, v);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .orElse(null);
    }

    /** 加载某虚拟电厂的在线资源快照（遥测缺失时 telemetryAvailable=false）。 */
    public List<ResourceSnapshot> loadSnapshots(Long portfolioId) {
        List<ResourceSnapshot> out = new ArrayList<>();
        for (VppResource r : vppResourceRepository.findByPortfolioIdAndStatus(portfolioId, STATUS_ONLINE)) {
            out.add(toSnapshot(r));
        }
        return out;
    }

    /** 生成调度建议（不落库、不下发）。 */
    public DispatchOutcome planForPortfolio(Long portfolioId, boolean exportAllowed) {
        vppPortfolioRepository.findById(portfolioId)
                .orElseThrow(() -> BizException.notFound("error.vpp.portfolio.not.found"));
        VppDispatchOptions opt = resolveOptions();
        if (exportAllowed) {
            opt = new VppDispatchOptions(opt.chargeTempMinC(), opt.essSocMinPercent(), true);
        }
        return dispatchPlan(loadSnapshots(portfolioId), Instant.now(), opt,
                resolveContext(portfolioId, Instant.now()));
    }

    /** 历史指令（按创建时间倒序）。 */
    public List<VppDispatchOrder> listOrders(Long portfolioId) {
        vppPortfolioRepository.findById(portfolioId)
                .orElseThrow(() -> BizException.notFound("error.vpp.portfolio.not.found"));
        return vppDispatchOrderRepository.findByPortfolioIdOrderByCreatedAtDesc(portfolioId);
    }

    /**
     * 把调度建议落库为指令；影子模式下 {@code shadow=true} 且不调用下发通道。
     *
     * @param portfolioId 虚拟电厂 id
     * @param targets     指令目标
     * @param operatorId  操作人
     * @return 落库后的指令列表
     */
    @Transactional
    public List<VppDispatchOrder> issueOrders(Long portfolioId, List<CommandTarget> targets, Long operatorId) {
        vppPortfolioRepository.findById(portfolioId)
                .orElseThrow(() -> BizException.notFound("error.vpp.portfolio.not.found"));
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        boolean shadow = shadowMode();
        log.info("[VPP] 下发指令 portfolio={} size={} shadow={} operator={}",
                portfolioId, targets.size(), shadow, operatorId);

        List<VppDispatchOrder> orders = new ArrayList<>();
        for (CommandTarget t : targets) {
            if (t == null) {
                continue;
            }
            if (t.commandType() == null || !VALID_COMMAND_TYPES.contains(t.commandType())) {
                throw BizException.invalidParam("error.vpp.command.type.invalid", String.valueOf(t.commandType()));
            }
            VppResource resource = vppResourceRepository.findById(t.resourceId())
                    .orElseThrow(() -> BizException.notFound("error.vpp.resource.not.found"));
            VppDispatchOrder order = VppDispatchOrder.builder()
                    .portfolioId(portfolioId)
                    .resourceId(t.resourceId())
                    .commandType(t.commandType())
                    .targetW(t.targetW())
                    .startAt(t.startAt())
                    .endAt(t.endAt())
                    .status("ISSUED")
                    .shadow(shadow)
                    .issuedBy(operatorId)
                    .reason(t.reason())
                    .build();
            orders.add(vppDispatchOrderRepository.save(order));
            // 影子模式：绝不触碰下发通道
            if (!shadow) {
                deliver(resource.getAssetId(), t);
            }
        }
        return orders;
    }

    /** 真实下发（仅非影子模式调用）。失败只记日志，不回滚已落库的指令。 */
    private void deliver(Long assetId, CommandTarget t) {
        String deviceNo = deviceRepository.findByAssetId(assetId).stream()
                .map(Device::getDeviceNo)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse(null);
        if (deviceNo == null) {
            log.warn("[VPP] 资产 {} 未绑定设备号，跳过真实下发（指令 {}）", assetId, t.commandType());
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("commandType", t.commandType());
        params.put("targetW", nz(t.targetW()));
        params.put("startAt", t.startAt() == null ? null : t.startAt().toString());
        params.put("endAt", t.endAt() == null ? null : t.endAt().toString());
        params.put("reason", t.reason() == null ? "" : t.reason());
        try {
            deviceCommandService.issue(deviceNo, actionOf(t.commandType()), params);
        } catch (Exception e) {
            log.warn("[VPP] 指令下发失败 asset={} deviceNo={} command={}：{}",
                    assetId, deviceNo, t.commandType(), e.getMessage());
        }
    }

    /** 指令类型 → 设备动作。 */
    private static String actionOf(String commandType) {
        return switch (commandType) {
            case CMD_DERATE_PV -> "set_active_power";
            case CMD_CHARGE_ESS -> "enable_charge";
            case CMD_DISCHARGE_ESS -> "enable_discharge";
            case CMD_CURTAIL_CHARGER -> "set_output_power";
            case CMD_SET_CHARGER_POWER -> "set_charger_power";
            case CMD_START_GEN -> "start_generator";
            default -> "noop";
        };
    }

    /** 资源 → 快照。 */
    private ResourceSnapshot toSnapshot(VppResource r) {
        TelemetryLatest t =
                telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(r.getAssetId()).orElse(null);
        String type = r.getResourceType();
        if (t == null) {
            return new ResourceSnapshot(r.getId(), type, r.getRatedPowerW(), r.getAdjustableMinW(),
                    r.getAdjustableMaxW(), null, null, null, null, null, null, null, null, false);
        }
        if (PV.equals(type)) {
            BigDecimal current = t.getAcActivePowerW() != null ? t.getAcActivePowerW() : t.getMeterActivePowerW();
            return new ResourceSnapshot(r.getId(), type, r.getRatedPowerW(), r.getAdjustableMinW(),
                    r.getAdjustableMaxW(), current, t.getDeratePercent(), t.getIrradiance(),
                    null, null, null, null, null, current != null);
        }
        if (ESS.equals(type)) {
            BigDecimal tempMin = t.getTempMin() != null ? t.getTempMin() : t.getTemp();
            boolean usable = t.getPackVoltage() != null && (t.getCcl() != null || t.getDcl() != null);
            return new ResourceSnapshot(r.getId(), type, r.getRatedPowerW(), r.getAdjustableMinW(),
                    r.getAdjustableMaxW(), t.getPowerW(), null, null, t.getSoc(), tempMin,
                    t.getCcl(), t.getDcl(), t.getPackVoltage(), usable);
        }
        // CHARGER / CONTROLLABLE_LOAD / DIESEL_GEN
        BigDecimal current = t.getPowerW() != null ? t.getPowerW() : t.getAcActivePowerW();
        if (current == null) {
            current = t.getMeterActivePowerW();
        }
        return new ResourceSnapshot(r.getId(), type, r.getRatedPowerW(), r.getAdjustableMinW(),
                r.getAdjustableMaxW(), current, null, null, t.getSoc(), null, null, null, null,
                current != null);
    }

    // ===================== 工具 =====================

    /** 资源功率上限基准：adjustableMaxW 优先，缺失回退额定功率，再缺失回退 0。 */
    private static BigDecimal capOf(ResourceSnapshot r) {
        BigDecimal max = r.adjustableMaxW() != null ? r.adjustableMaxW() : r.ratedPowerW();
        return nz(max).max(ZERO);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
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

    private boolean boolConfig(String key, boolean fallback) {
        return systemConfigRepository.findByConfigKeyAndDeletedFalse(key)
                .map(SystemConfig::getConfigValue)
                .map(v -> v == null ? null : v.trim())
                .filter(v -> !v.isEmpty())
                .map(v -> "true".equalsIgnoreCase(v) || "1".equals(v) || "on".equalsIgnoreCase(v)
                        || "yes".equalsIgnoreCase(v))
                .orElse(fallback);
    }
}
