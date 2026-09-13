package com.claw.server.domain.payment;

import com.claw.server.common.api.BizException;
import com.claw.server.domain.clearing.SplitEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 通道下发计划（L1）：把账本侧分账腿转换成<b>可直接提交通道</b>的收款方明细。
 *
 * <p>本类是纯函数（无副作用、无 IO），集中落地落地设计附录 A.3 的三条通道硬约束：
 * <ol>
 *   <li><b>分账总额必须精确等于交易总额</b>（ABA 错误码 92）→ 每腿金额精确到分（{@value #CHANNEL_SCALE} 位，
 *       HALF_UP），四舍五入产生的<b>尾差由平台收入吸收</b>（{@value #TAIL_ABSORBER_PAYEE} →
 *       {@code PLATFORM_REVENUE}），吸收后仍不平则显式抛错（绝不提交不平请求）；</li>
 *   <li><b>单次 ≤{@value #MAX_BENEFICIARIES} 受益人</b>（错误码 25）→ 超出按 <b>分片序号</b>（1-based）拆单，
 *       同一 {@code basisRef} 用 {@link ChannelPlan#shardInstructionNo(String, int)} 关联对账；</li>
 *   <li><b>受益人必须是 ABA 账户持有人 / ABA MID</b> → 逐腿做前置校验，非平台自有方缺 ABA 账户号即抛错，
 *       由调用方降级到账即清 / 周期批量（不静默丢弃、不臆造账户）。</li>
 * </ol>
 *
 * <p><b>平台自有方豁免</b>：{@value #TAIL_ABSORBER_PAYEE}（平台收入）是平台自身的商户账户，其 ABA MID
 * 由通道侧的商户配置承载，不来自 {@code virtual_subaccount.external_sub_no}，故允许 ABA 账户号为空。
 */
@Component
@Slf4j
public class ChannelSplitPlanner {

    /** 通道单次受益人上限（ABA 错误码 25：单次 ≤10 受益人）。 */
    public static final int MAX_BENEFICIARIES = 10;

    /** 尾差吸收方（平台收入，唯一公司自有科目）。 */
    public static final String TAIL_ABSORBER_PAYEE = "PLATFORM";

    /** 通道金额标度：精确到分。 */
    public static final int CHANNEL_SCALE = 2;

    /** 货币金额舍入方式（与账本 SCALE 口径一致：四舍五入）。 */
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** 收款方未绑定 ABA 账户/MID（降级路径 C）。 */
    private static final int CODE_ABA_MISSING = 42276;

    /** 尾差无法吸收、分账总额不平。 */
    private static final int CODE_TAIL_UNBALANCED = 42277;

    /**
     * 生成通道下发计划。
     *
     * @param basisRef   清分依据单号（订单号；用于分片指令号关联）
     * @param total      交易总额（与通道侧收单金额必须一致）
     * @param splitLegs  账本侧分账腿（{@code SplitEngine.compute} 的输出，金额标度 4 位）
     * @param currency   币种（USD/KHR）
     * @param resolver   收款方 ABA 账户解析器（可为 {@code null}，此时非平台腿一律判定为未绑定）
     * @return 通道下发计划（Σ腿金额 == 交易总额，已按 ≤10 受益人分片）
     * @throws BizException 入参非法 / 收款方未绑定 ABA 账户 / 尾差无法吸收
     */
    public ChannelPlan plan(String basisRef, BigDecimal total, List<SplitEngine.SplitLeg> splitLegs,
                            String currency, PayeeAbaRefResolver resolver) {
        if (splitLegs == null || splitLegs.isEmpty() || total == null || total.signum() <= 0) {
            throw BizException.invalidParam("error.clearing.request.invalid");
        }
        String ccy = (currency == null || currency.isBlank()) ? "USD" : currency;
        BigDecimal targetTotal = total.setScale(CHANNEL_SCALE, ROUNDING);

        // ① 逐腿精确到分 + ② 收款方 ABA 账户前置校验（A.3-1 准入规则）
        List<ChannelLeg> legs = new ArrayList<>(splitLegs.size());
        BigDecimal allocated = BigDecimal.ZERO;
        for (SplitEngine.SplitLeg leg : splitLegs) {
            String payeeType = leg.payeeType();
            String abaRef = resolveAbaRef(resolver, payeeType, ccy);
            if (isBlank(abaRef) && !TAIL_ABSORBER_PAYEE.equals(payeeType)) {
                throw BizException.of(CODE_ABA_MISSING, "error.clearing.payee.aba.missing", payeeType);
            }
            BigDecimal amount = leg.amount().setScale(CHANNEL_SCALE, ROUNDING);
            legs.add(new ChannelLeg(payeeType, abaRef, amount, 0));
            allocated = allocated.add(amount);
        }

        // ③ 尾差吸收：差额归平台收入，保证 Σ腿 == 交易总额（通道错误码 92 硬约束）
        BigDecimal tail = targetTotal.subtract(allocated);
        if (tail.signum() != 0) {
            int absorberIndex = indexOfPayee(legs, TAIL_ABSORBER_PAYEE);
            if (absorberIndex < 0) {
                throw BizException.of(CODE_TAIL_UNBALANCED, "error.clearing.split.tail.unabsorbed", allocated, targetTotal);
            }
            ChannelLeg absorber = legs.get(absorberIndex);
            BigDecimal adjusted = absorber.amount().add(tail);
            if (adjusted.signum() < 0) {
                throw BizException.of(CODE_TAIL_UNBALANCED, "error.clearing.split.tail.unabsorbed", allocated, targetTotal);
            }
            legs.set(absorberIndex, new ChannelLeg(absorber.payeeType(), absorber.payeeAbaRef(), adjusted, 0));
            allocated = allocated.add(tail);
            log.info("[ChannelSplitPlanner] 尾差 {} 由平台收入吸收 basisRef={} 交易总额={}", tail, basisRef, targetTotal);
        }
        if (allocated.compareTo(targetTotal) != 0) {
            throw BizException.of(CODE_TAIL_UNBALANCED, "error.clearing.split.tail.unabsorbed", allocated, targetTotal);
        }

        // ④ ≤10 受益人分片：写入 1-based 分片序号（超出时同一 basisRef 用 #序号 关联对账）
        int shardCount = (legs.size() - 1) / MAX_BENEFICIARIES + 1;
        for (int i = 0; i < legs.size(); i++) {
            ChannelLeg leg = legs.get(i);
            legs.set(i, new ChannelLeg(leg.payeeType(), leg.payeeAbaRef(), leg.amount(),
                    i / MAX_BENEFICIARIES + 1));
        }
        if (shardCount > 1) {
            log.info("[ChannelSplitPlanner] 受益人数 {} 超过单次上限 {}，拆为 {} 单 basisRef={}",
                    legs.size(), MAX_BENEFICIARIES, shardCount, basisRef);
        }
        return new ChannelPlan(basisRef, ccy, List.copyOf(legs), tail, shardCount, targetTotal);
    }

    /**
     * 解析收款方 ABA 账户号。
     *
     * @param resolver  解析器（可为 null）
     * @param payeeType 收款方类型
     * @param currency  币种
     * @return ABA 账户号/MID；无法解析时为 {@code null}
     */
    private static String resolveAbaRef(PayeeAbaRefResolver resolver, String payeeType, String currency) {
        if (resolver == null) {
            return null;
        }
        return resolver.resolve(payeeType, currency).orElse(null);
    }

    private static int indexOfPayee(List<ChannelLeg> legs, String payeeType) {
        for (int i = 0; i < legs.size(); i++) {
            if (payeeType.equals(legs.get(i).payeeType())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 单条通道收款方明细。
     *
     * @param payeeType   收款方类型
     * @param payeeAbaRef 收款方 ABA 账户号/MID（平台自有方为 {@code null}）
     * @param amount      金额（已精确到分，已含尾差调整）
     * @param shardIndex  分片序号（1-based）
     */
    public record ChannelLeg(String payeeType, String payeeAbaRef, BigDecimal amount, int shardIndex) {
    }

    /**
     * 通道下发计划。
     *
     * @param basisRef     依据单号
     * @param currency     币种
     * @param legs         收款方明细（已精确到分、已含尾差、已带分片序号）
     * @param tailAbsorbed 被平台收入吸收的尾差（正=平台少收，负=平台多补）
     * @param shardCount   分片数（1 表示单次即可下发）
     * @param total        交易总额（精确到分）
     */
    public record ChannelPlan(String basisRef, String currency, List<ChannelLeg> legs,
                              BigDecimal tailAbsorbed, int shardCount, BigDecimal total) {

        /** 受益人数。 */
        public int beneficiaries() {
            return legs.size();
        }

        /** 是否需要拆单（受益人数 &gt; {@value ChannelSplitPlanner#MAX_BENEFICIARIES}）。 */
        public boolean shardRequired() {
            return shardCount > 1;
        }

        /**
         * 按分片序号分组（每片 ≤{@value ChannelSplitPlanner#MAX_BENEFICIARIES} 个受益人），顺序稳定。
         *
         * @return 分片列表，第 0 片对应 shardIndex=1
         */
        public List<List<ChannelLeg>> shards() {
            Map<Integer, List<ChannelLeg>> byShard = new TreeMap<>();
            for (ChannelLeg leg : legs) {
                byShard.computeIfAbsent(leg.shardIndex(), k -> new ArrayList<>()).add(leg);
            }
            List<List<ChannelLeg>> result = new ArrayList<>(byShard.size());
            for (List<ChannelLeg> shard : byShard.values()) {
                result.add(List.copyOf(shard));
            }
            return List.copyOf(result);
        }

        /** 某分片的受益人合计金额。 */
        public BigDecimal shardAmount(int shardIndex) {
            return legs.stream()
                    .filter(l -> l.shardIndex() == shardIndex)
                    .map(ChannelLeg::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /**
         * 分片指令号：多分片时在指令号后追加 {@code #序号}，保证同一 {@code basisRef} 对账可关联。
         *
         * @param instructionNo 原始清分指令号
         * @param shardIndex    分片序号（1-based）
         * @return 分片指令号；单分片时原样返回
         */
        public String shardInstructionNo(String instructionNo, int shardIndex) {
            return shardCount > 1 ? instructionNo + "#" + shardIndex : instructionNo;
        }

        /** 转换为通道 SPI 的收款方明细。 */
        public List<ClearingChannelGateway.ChannelPayee> toChannelPayees() {
            return legs.stream()
                    .map(l -> new ClearingChannelGateway.ChannelPayee(
                            l.payeeType(), l.payeeAbaRef(), l.amount(), l.shardIndex()))
                    .toList();
        }
    }
}
