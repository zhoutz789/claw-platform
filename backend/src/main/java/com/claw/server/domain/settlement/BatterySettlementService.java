package com.claw.server.domain.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 锂电池换电能源结算引擎（锂电池 BMS 对接方案 Phase D，落地方案 §9 全部业务规则）。
 *
 * <p><b>计量铁律</b>：以 BMS 累计充/放电 Wh 计数器计算「custody 内净能量」，<b>禁用 SOC% 差值</b>
 * （SOC 非线性且随 SOH 漂移，会误结算）。
 *
 * <p><b>两次交接 / custody 净能量（✅ 已定）</b>：一次换电 = 归还 depleted 包 A + 取 full 包 B。
 * 本引擎对「单块包的 custody 事件链（取→还）」结算：用户只对其持有的那块包（A）的净能量变化付费；
 * 取 B 时不结算 B 的满电余量（B 是下一 custody 起点，其能量在归还 B 时再算），两块包电量差不混算，
 * 杜绝重复计费。
 *
 * <p><b>结算规则（✅ 已定）</b>：
 * <ul>
 *   <li>ΔWh = 还A净能量 − 取A净能量；ΔWh&lt;0 扣电费（|ΔWh|×实际电价），ΔWh&gt;0 反补电费。</li>
 *   <li>补偿口径（✅ 仅平台计量充电）：用户用自建/自充补满后归还的净增，仅"平台计量充电"部分才补偿，防套利。</li>
 *   <li>损耗（充电往返效率/自放电/均衡）用户<b>不担</b>，由投资者+平台共担（lossBearer=INVESTOR_PLATFORM）；
 *       用户仅对其净取用能量付费，平台补能的损耗不在用户账单内。</li>
 *   <li>服务费每使用一次，0.1–0.2 USD（由配置注入），复用进充电桩收费体系。</li>
 *   <li>分成基数 = 服务费（+平台吸收损耗后的净 margin）；电费 pass-through 不分成。</li>
 * </ul>
 *
 * <p>纯计算服务，无 DB / 无 Spring 依赖，便于单测与边缘网关/调度复用。
 */
public class BatterySettlementService {

    /** 损耗承担方（✅ 已定：末端用户不担，投资者 + 平台共担）。 */
    public static final String LOSS_BEARER = "INVESTOR_PLATFORM";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int SCALE = 4;

    // ===================== 输入模型 =====================

    /** BMS 累计计数器快照（单位 Wh）。 */
    public record CumulativeEnergy(BigDecimal chargeWh, BigDecimal dischargeWh) {
    }

    /** 单块电池的一段 custody 事件链：取用 → 归还。 */
    public record CustodyEpisode(
            Long assetId,
            Instant takenAt,
            Instant returnedAt,
            CumulativeEnergy atTake,
            CumulativeEnergy atReturn,
            /** 平台计量充电补足的 Wh（用于"仅平台计量充电"补偿口径判断）。 */
            BigDecimal meteredRechargeWh) {
    }

    /** 计价上下文（实际电价按交接触发时刻 TOU 取；服务费由平台配置注入）。 */
    public record PricingContext(
            BigDecimal electricityPricePerWh,
            BigDecimal serviceFee,
            String currency,
            boolean compensateOnlyMeteredCharge) {
    }

    /** 三方分成比例（投资者 / 场地方 / 平台，建议和为 1）。 */
    public record RevenueSplit(BigDecimal investorPct, BigDecimal stationPct, BigDecimal platformPct) {
    }

    // ===================== 输出模型 =====================

    public record SettlementLine(String type, String description, BigDecimal amount) {
    }

    public record SettlementResult(
            Long assetId,
            BigDecimal consumptionWh,
            BigDecimal creditWh,
            BigDecimal electricityFee,
            BigDecimal credit,
            BigDecimal serviceFee,
            BigDecimal total,
            String currency,
            String lossBearer,
            List<SettlementLine> lines) {
    }

    public record SplitResult(BigDecimal investor, BigDecimal station, BigDecimal platform) {
    }

    // ===================== 计算 =====================

    /**
     * 结算一段 custody 事件链（对应换电中"归还的包 A"）。
     *
     * @param ep      单块包的取→还事件链
     * @param pricing 计价上下文
     */
    public SettlementResult settleSwap(CustodyEpisode ep, PricingContext pricing) {
        BigDecimal netBefore = nullSafe(ep.atTake().chargeWh()).subtract(nullSafe(ep.atTake().dischargeWh()));
        BigDecimal netAfter = nullSafe(ep.atReturn().chargeWh()).subtract(nullSafe(ep.atReturn().dischargeWh()));

        // ΔWh = 还净能量 − 取净能量（负=用户净消耗）
        BigDecimal deltaWh = netAfter.subtract(netBefore);
        BigDecimal consumptionWh = deltaWh.signum() < 0 ? deltaWh.abs() : ZERO;
        BigDecimal creditWh = deltaWh.signum() > 0 ? deltaWh : ZERO;

        // 多用扣电费：净消耗 × 实际电价
        BigDecimal electricityFee = scale(consumptionWh.multiply(nullSafe(pricing.electricityPricePerWh())));

        // 少补反补：仅"平台计量充电"部分才补偿（防套利）
        BigDecimal creditedWh = ZERO;
        if (creditWh.signum() > 0) {
            BigDecimal metered = pricing.compensateOnlyMeteredCharge()
                    ? nullSafe(ep.meteredRechargeWh()) : creditWh;
            creditedWh = creditWh.min(metered);
        }
        BigDecimal credit = scale(creditedWh.multiply(nullSafe(pricing.electricityPricePerWh())));

        BigDecimal serviceFee = scale(nullSafe(pricing.serviceFee()));
        // 用户总账：电费 + 服务费 − 反补（反补为负向冲抵）。损耗不在用户账单内。
        BigDecimal total = scale(electricityFee.add(serviceFee).subtract(credit));

        List<SettlementLine> lines = new ArrayList<>();
        if (consumptionWh.signum() > 0) {
            lines.add(new SettlementLine("ELECTRICITY_FEE",
                    "custody 内净消耗 " + consumptionWh + " Wh × 实际电价（损耗由" + LOSS_BEARER + "承担）",
                    electricityFee));
        }
        if (credit.signum() > 0) {
            lines.add(new SettlementLine("ELECTRICITY_CREDIT",
                    "净增能量 " + creditedWh + " Wh 反补（仅平台计量充电）", credit.negate()));
        }
        lines.add(new SettlementLine("SERVICE_FEE", "换电/使用服务费（0.1–0.2 USD 平台配置）", serviceFee));

        return new SettlementResult(ep.assetId(), consumptionWh, creditWh, electricityFee, credit,
                serviceFee, total, currencyOf(pricing.currency()), LOSS_BEARER, lines);
    }

    /** 服务费三方分成（基数=服务费，电费不分成）。 */
    public SplitResult splitServiceFee(BigDecimal serviceFee, RevenueSplit split) {
        BigDecimal base = nullSafe(serviceFee);
        return new SplitResult(
                scale(base.multiply(nullSafe(split.investorPct()))),
                scale(base.multiply(nullSafe(split.stationPct()))),
                scale(base.multiply(nullSafe(split.platformPct()))));
    }

    private static BigDecimal nullSafe(BigDecimal v) {
        return v != null ? v : ZERO;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static String currencyOf(String c) {
        return c != null && !c.isBlank() ? c : "USD";
    }
}
