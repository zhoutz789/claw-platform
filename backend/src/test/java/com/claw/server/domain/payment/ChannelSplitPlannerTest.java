package com.claw.server.domain.payment;

import com.claw.server.common.api.BizException;
import com.claw.server.domain.clearing.SplitEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChannelSplitPlanner} 单元测试（纯函数，无 Docker/PG）。
 *
 * <p>覆盖落地设计附录 A.3 的三条通道硬约束：
 * <ol>
 *   <li>分账总额必须等于交易总额（错误码 92）→ 尾差由平台收入吸收；</li>
 *   <li>单次 ≤10 受益人（错误码 25）→ 超出按分片序号拆单；</li>
 *   <li>受益人必须是 ABA 账户持有人/MID → 缺少 ABA 账户号即前置校验失败（降级路径 C）。</li>
 * </ol>
 */
class ChannelSplitPlannerTest {

    private final ChannelSplitPlanner planner = new ChannelSplitPlanner();

    /** 常规解析器：平台自有方返回空（豁免），其余返回 ABA 账户号。 */
    private static final PayeeAbaRefResolver RESOLVER = (payeeType, currency) ->
            "PLATFORM".equals(payeeType) ? Optional.empty() : Optional.of("ABA-" + payeeType);

    private static SplitEngine.SplitLeg leg(String payee, String amount) {
        return new SplitEngine.SplitLeg(payee, new BigDecimal(amount), "T+0", 10, 1L);
    }

    private static BigDecimal sum(ChannelSplitPlanner.ChannelPlan plan) {
        return plan.legs().stream()
                .map(ChannelSplitPlanner.ChannelLeg::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ChannelSplitPlanner.ChannelLeg legOf(ChannelSplitPlanner.ChannelPlan plan, String payee) {
        return plan.legs().stream()
                .filter(l -> l.payeeType().equals(payee))
                .findFirst()
                .orElseThrow();
    }

    // ===================== 金额守恒（无尾差）=====================

    @Test
    void fourLegs_noTailDifference_sumsToTransactionTotal() {
        List<SplitEngine.SplitLeg> legs = List.of(
                leg("PLATFORM", "5.0000"), leg("STATION", "10.0000"),
                leg("LOGISTICS", "5.0000"), leg("MANUFACTURER", "80.0000"));

        ChannelSplitPlanner.ChannelPlan plan = planner.plan("ORD-1", new BigDecimal("100.00"), legs, "USD", RESOLVER);

        assertEquals(4, plan.beneficiaries());
        assertEquals(0, plan.tailAbsorbed().compareTo(BigDecimal.ZERO));
        assertEquals(0, sum(plan).compareTo(plan.total()));
        assertEquals(1, plan.shardCount());
        assertFalse(plan.shardRequired());
        assertEquals(0, legOf(plan, "MANUFACTURER").amount().compareTo(new BigDecimal("80.00")));
        assertEquals("ABA-STATION", legOf(plan, "STATION").payeeAbaRef());
    }

    // ===================== 尾差吸收（错误码 92）=====================

    @Test
    void tailDifference_isAbsorbedByPlatformRevenue() {
        // 0.0250 + 0.0250 各自四舍五入到分 → 0.03 + 0.03 = 0.06，超出交易总额 0.05 共 0.01
        List<SplitEngine.SplitLeg> legs = List.of(
                leg("PLATFORM", "0.0250"), leg("MANUFACTURER", "0.0250"));

        ChannelSplitPlanner.ChannelPlan plan = planner.plan("ORD-TAIL", new BigDecimal("0.05"), legs, "USD", RESOLVER);

        assertEquals(0, plan.tailAbsorbed().compareTo(new BigDecimal("-0.01")));
        assertEquals(0, legOf(plan, "PLATFORM").amount().compareTo(new BigDecimal("0.02")));
        assertEquals(0, legOf(plan, "MANUFACTURER").amount().compareTo(new BigDecimal("0.03")));
        assertEquals(0, sum(plan).compareTo(new BigDecimal("0.05")), "吸收尾差后 Σ腿必须精确等于交易总额");
    }

    @Test
    void positiveTail_isAbsorbedByPlatformRevenue() {
        // 0.0149 + 0.0149 → 0.01 + 0.01 = 0.02，少于交易总额 0.03 共 0.01 → 平台补足
        List<SplitEngine.SplitLeg> legs = List.of(
                leg("PLATFORM", "0.0149"), leg("MANUFACTURER", "0.0149"));

        ChannelSplitPlanner.ChannelPlan plan = planner.plan("ORD-UP", new BigDecimal("0.03"), legs, "USD", RESOLVER);

        assertEquals(0, plan.tailAbsorbed().compareTo(new BigDecimal("0.01")));
        assertEquals(0, sum(plan).compareTo(new BigDecimal("0.03")));
    }

    @Test
    void tailUnabsorbable_withoutPlatformLeg_throws() {
        List<SplitEngine.SplitLeg> legs = List.of(
                leg("STATION", "0.0250"), leg("LOGISTICS", "0.0250"));

        BizException ex = assertThrows(BizException.class,
                () -> planner.plan("ORD-NP", new BigDecimal("0.05"), legs, "USD", RESOLVER));
        assertEquals(42277, ex.getCode());
    }

    // ===================== 收款方 ABA 前置校验（准入规则）=====================

    @Test
    void missingAbaRef_throwsAbaMissing() {
        PayeeAbaRefResolver noStation = (payeeType, currency) ->
                "STATION".equals(payeeType) ? Optional.empty() : Optional.of("ABA-" + payeeType);
        List<SplitEngine.SplitLeg> legs = List.of(
                leg("PLATFORM", "5.0000"), leg("STATION", "10.0000"), leg("MANUFACTURER", "85.0000"));

        BizException ex = assertThrows(BizException.class,
                () -> planner.plan("ORD-2", new BigDecimal("100.00"), legs, "USD", noStation));
        assertEquals(42276, ex.getCode());
    }

    @Test
    void platformLeg_isExemptFromAbaCheck() {
        // PLATFORM 无 ABA 账户号也应通过（其 MID 由通道侧商户配置承载，不来自虚拟子户）
        List<SplitEngine.SplitLeg> legs = List.of(
                leg("PLATFORM", "100.0000"));

        ChannelSplitPlanner.ChannelPlan plan = planner.plan("ORD-3", new BigDecimal("100.00"), legs, "USD", RESOLVER);

        assertEquals(1, plan.beneficiaries());
        assertNull(legOf(plan, "PLATFORM").payeeAbaRef(), "平台自有方豁免 ABA 账户前置校验");
    }

    @Test
    void nullResolver_treatsNonPlatformLegAsMissing() {
        List<SplitEngine.SplitLeg> legs = List.of(leg("MANUFACTURER", "100.0000"));

        BizException ex = assertThrows(BizException.class,
                () -> planner.plan("ORD-4", new BigDecimal("100.00"), legs, "USD", null));
        assertEquals(42276, ex.getCode());
    }

    // ===================== ≤10 受益人拆分（错误码 25）=====================

    @Test
    void moreThan10Beneficiaries_isSharded_withShardIndex() {
        List<SplitEngine.SplitLeg> legs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            legs.add(leg("MANUFACTURER", "1.0000"));
        }

        ChannelSplitPlanner.ChannelPlan plan = planner.plan("ORD-MANY", new BigDecimal("12.00"), legs, "USD", RESOLVER);

        assertEquals(12, plan.beneficiaries());
        assertEquals(2, plan.shardCount());
        assertTrue(plan.shardRequired());

        List<List<ChannelSplitPlanner.ChannelLeg>> shards = plan.shards();
        assertEquals(2, shards.size());
        assertEquals(10, shards.get(0).size());
        assertEquals(2, shards.get(1).size());
        assertEquals(1, shards.get(0).get(0).shardIndex());
        assertEquals(1, shards.get(0).get(9).shardIndex());
        assertEquals(2, shards.get(1).get(0).shardIndex());
        assertEquals(2, shards.get(1).get(1).shardIndex());

        assertEquals(0, plan.shardAmount(1).compareTo(new BigDecimal("10.00")));
        assertEquals(0, plan.shardAmount(2).compareTo(new BigDecimal("2.00")));
        assertEquals(0, sum(plan).compareTo(new BigDecimal("12.00")));
    }

    @Test
    void shardInstructionNo_appendsIndexOnlyWhenSharded() {
        List<SplitEngine.SplitLeg> single = List.of(leg("MANUFACTURER", "100.0000"));
        ChannelSplitPlanner.ChannelPlan one =
                planner.plan("ORD-S1", new BigDecimal("100.00"), single, "USD", RESOLVER);
        assertEquals("CI-R1-1-AAAAAA", one.shardInstructionNo("CI-R1-1-AAAAAA", 1));

        List<SplitEngine.SplitLeg> many = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            many.add(leg("MANUFACTURER", "1.0000"));
        }
        ChannelSplitPlanner.ChannelPlan two =
                planner.plan("ORD-S2", new BigDecimal("11.00"), many, "USD", RESOLVER);
        assertEquals("CI-R1-1-BBBBBB#1", two.shardInstructionNo("CI-R1-1-BBBBBB", 1));
        assertEquals("CI-R1-1-BBBBBB#2", two.shardInstructionNo("CI-R1-1-BBBBBB", 2));
    }

    // ===================== 非法入参 =====================

    @Test
    void emptyLegs_throwsRequestInvalid() {
        assertThrows(BizException.class,
                () -> planner.plan("ORD-5", new BigDecimal("100.00"), List.of(), "USD", RESOLVER));
    }

    @Test
    void nonPositiveTotal_throwsRequestInvalid() {
        List<SplitEngine.SplitLeg> legs = List.of(leg("MANUFACTURER", "0.0000"));

        BizException ex = assertThrows(BizException.class,
                () -> planner.plan("ORD-6", BigDecimal.ZERO, legs, "USD", RESOLVER));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }

    @Test
    void blankCurrency_defaultsToUsd() {
        List<SplitEngine.SplitLeg> legs = List.of(leg("MANUFACTURER", "100.0000"));

        ChannelSplitPlanner.ChannelPlan plan = planner.plan("ORD-7", new BigDecimal("100.00"), legs, "  ", RESOLVER);

        assertEquals("USD", plan.currency());
    }
}
