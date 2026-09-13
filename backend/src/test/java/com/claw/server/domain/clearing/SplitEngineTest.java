package com.claw.server.domain.clearing;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.RuleBasis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * {@link SplitEngine} 单元测试（纯函数，无 Docker/PG）。
 * 覆盖：四腿守恒 + 残差归厂家、priority 顺序、TIER 档位、厂家过滤、无规则/越界抛错。
 */
@ExtendWith(MockitoExtension.class)
class SplitEngineTest {

    @Mock
    private SettlementRuleRepository settlementRuleRepository;

    @InjectMocks
    private SplitEngine splitEngine;

    private static final String SCENE = "CONSIGNMENT_SCAN";

    private static SettlementRule rule(String payee, RuleBasis basis, String rate, String fixed,
                                       String tierJson, int priority, Long manufacturerId) {
        return SettlementRule.builder()
                .payeeType(payee)
                .basis(basis)
                .rate(rate == null ? null : new BigDecimal(rate))
                .fixedAmount(fixed == null ? null : new BigDecimal(fixed))
                .tierJson(tierJson)
                .priority(priority)
                .currency("USD")
                .settleCycle("T+0")
                .manufacturerId(manufacturerId)
                .build();
    }

    private void stubScene(SettlementRule... rules) {
        when(settlementRuleRepository
                .findByBizSceneAndStatusAndDeletedFalseOrderByPriorityAsc(SCENE, "ACTIVE"))
                .thenReturn(List.of(rules));
    }

    private static BigDecimal sum(List<SplitEngine.SplitLeg> legs) {
        return legs.stream().map(SplitEngine.SplitLeg::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static SplitEngine.SplitLeg legOf(List<SplitEngine.SplitLeg> legs, String payee) {
        return legs.stream().filter(l -> l.payeeType().equals(payee)).findFirst().orElseThrow();
    }

    // ===================== 守恒 + 残差 =====================

    @Test
    void fourLegs_conserved_andResidueToManufacturer() {
        stubScene(
                rule("PLATFORM", RuleBasis.RATE, "0.05", null, null, 1, null),
                rule("STATION", RuleBasis.RATE, "0.10", null, null, 10, null),
                rule("LOGISTICS", RuleBasis.RATE, "0.05", null, null, 20, null),
                rule("MANUFACTURER", RuleBasis.RATE, "1.0", null, null, 99, null));

        List<SplitEngine.SplitLeg> legs = splitEngine.compute(SCENE, new BigDecimal("100"), null, "USD");

        assertEquals(4, legs.size());
        assertEquals(0, sum(legs).compareTo(new BigDecimal("100")));
        assertEquals(0, legOf(legs, "PLATFORM").amount().compareTo(new BigDecimal("5.0000")));
        assertEquals(0, legOf(legs, "STATION").amount().compareTo(new BigDecimal("10.0000")));
        assertEquals(0, legOf(legs, "LOGISTICS").amount().compareTo(new BigDecimal("5.0000")));
        assertEquals(0, legOf(legs, "MANUFACTURER").amount().compareTo(new BigDecimal("80.0000")));
    }

    @Test
    void legs_areOrderedByPriorityAscending() {
        stubScene(
                rule("PLATFORM", RuleBasis.RATE, "0.05", null, null, 1, null),
                rule("STATION", RuleBasis.RATE, "0.10", null, null, 10, null),
                rule("LOGISTICS", RuleBasis.RATE, "0.05", null, null, 20, null),
                rule("MANUFACTURER", RuleBasis.RATE, "1.0", null, null, 99, null));

        List<SplitEngine.SplitLeg> legs = splitEngine.compute(SCENE, new BigDecimal("100"), null, "USD");

        assertEquals(List.of(1, 10, 20, 99), legs.stream().map(SplitEngine.SplitLeg::priority).toList());
    }

    @Test
    void fixedBasis_usesFixedAmount() {
        stubScene(
                rule("PLATFORM", RuleBasis.FIXED, null, "3.50", null, 1, null),
                rule("MANUFACTURER", RuleBasis.RATE, "1.0", null, null, 99, null));

        List<SplitEngine.SplitLeg> legs = splitEngine.compute(SCENE, new BigDecimal("100"), null, "USD");

        assertEquals(0, legOf(legs, "PLATFORM").amount().compareTo(new BigDecimal("3.5000")));
        assertEquals(0, legOf(legs, "MANUFACTURER").amount().compareTo(new BigDecimal("96.5000")));
    }

    // ===================== TIER =====================

    @Test
    void tier_selectsBracketByTotal() {
        stubScene(
                rule("STATION", RuleBasis.TIER, null, null,
                        "[{\"upTo\":1000,\"rate\":0.10},{\"rate\":0.05}]", 10, null),
                rule("MANUFACTURER", RuleBasis.RATE, "1.0", null, null, 99, null));

        List<SplitEngine.SplitLeg> legs = splitEngine.compute(SCENE, new BigDecimal("500"), null, "USD");

        assertEquals(0, legOf(legs, "STATION").amount().compareTo(new BigDecimal("50.0000")));
        assertEquals(0, legOf(legs, "MANUFACTURER").amount().compareTo(new BigDecimal("450.0000")));
    }

    @Test
    void tier_fallsBackToCatchAllBracket() {
        stubScene(
                rule("STATION", RuleBasis.TIER, null, null,
                        "[{\"upTo\":1000,\"rate\":0.10},{\"rate\":0.05}]", 10, null),
                rule("MANUFACTURER", RuleBasis.RATE, "1.0", null, null, 99, null));

        List<SplitEngine.SplitLeg> legs = splitEngine.compute(SCENE, new BigDecimal("5000"), null, "USD");

        assertEquals(0, legOf(legs, "STATION").amount().compareTo(new BigDecimal("250.0000")));
    }

    @Test
    void tier_invalidJson_throws() {
        stubScene(
                rule("STATION", RuleBasis.TIER, null, null, "not-a-json", 10, null),
                rule("MANUFACTURER", RuleBasis.RATE, "1.0", null, null, 99, null));

        BizException ex = assertThrows(BizException.class,
                () -> splitEngine.compute(SCENE, new BigDecimal("100"), null, "USD"));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }

    // ===================== 厂家过滤 =====================

    @Test
    void manufacturerSpecificRule_excludedForOtherManufacturer() {
        stubScene(
                rule("PLATFORM", RuleBasis.RATE, "0.05", null, null, 1, null),
                rule("PLATFORM", RuleBasis.RATE, "0.20", null, null, 1, 88L),
                rule("MANUFACTURER", RuleBasis.RATE, "1.0", null, null, 99, null));

        List<SplitEngine.SplitLeg> legs = splitEngine.compute(SCENE, new BigDecimal("100"), 77L, "USD");

        // 厂家 88 的专属规则被排除 → 不含 20.0000 的腿
        assertTrue(legs.stream().noneMatch(l -> l.amount().compareTo(new BigDecimal("20.0000")) == 0));
        assertEquals(2, legs.size());
    }

    @Test
    void manufacturerSpecificRule_includedForMatchingManufacturer() {
        stubScene(
                rule("PLATFORM", RuleBasis.RATE, "0.05", null, null, 1, null),
                rule("PLATFORM", RuleBasis.RATE, "0.20", null, null, 1, 88L),
                rule("MANUFACTURER", RuleBasis.RATE, "1.0", null, null, 99, null));

        List<SplitEngine.SplitLeg> legs = splitEngine.compute(SCENE, new BigDecimal("100"), 88L, "USD");

        assertTrue(legs.stream().anyMatch(l -> l.amount().compareTo(new BigDecimal("20.0000")) == 0));
    }

    // ===================== 失败路径 =====================

    @Test
    void noRules_throwsNotFound() {
        stubScene();

        BizException ex = assertThrows(BizException.class,
                () -> splitEngine.compute(SCENE, new BigDecimal("100"), null, "USD"));
        assertEquals(BizException.NOT_FOUND, ex.getCode());
    }

    @Test
    void overAllocated_negativeResidue_throwsNotBalanced() {
        stubScene(
                rule("PLATFORM", RuleBasis.RATE, "0.8", null, null, 1, null),
                rule("STATION", RuleBasis.RATE, "0.5", null, null, 10, null),
                rule("MANUFACTURER", RuleBasis.RATE, "1.0", null, null, 99, null));

        BizException ex = assertThrows(BizException.class,
                () -> splitEngine.compute(SCENE, new BigDecimal("100"), null, "USD"));
        assertEquals(42261, ex.getCode());
    }

    @Test
    void missingRate_throwsRuleInvalid() {
        stubScene(
                rule("PLATFORM", RuleBasis.RATE, null, null, null, 1, null),
                rule("MANUFACTURER", RuleBasis.RATE, "1.0", null, null, 99, null));

        BizException ex = assertThrows(BizException.class,
                () -> splitEngine.compute(SCENE, new BigDecimal("100"), null, "USD"));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }

    @Test
    void negativeTotal_throwsRequestInvalid() {
        BizException ex = assertThrows(BizException.class,
                () -> splitEngine.compute(SCENE, new BigDecimal("-1"), null, "USD"));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }

    // ===================== 残差显式化（V132 · RuleBasis.RESIDUAL）=====================

    @Test
    void residualBasis_receivesRemainder_withoutRate() {
        stubScene(
                rule("PLATFORM", RuleBasis.RATE, "0.05", null, null, 1, null),
                rule("MANUFACTURER", RuleBasis.RESIDUAL, null, null, null, 99, null));

        List<SplitEngine.SplitLeg> legs = splitEngine.compute(SCENE, new BigDecimal("100"), null, "USD");

        assertEquals(2, legs.size());
        assertEquals(0, sum(legs).compareTo(new BigDecimal("100")));
        // RESIDUAL 无需 rate/fixed/tier，直接拿残差：100 − 5 = 95
        assertEquals(0, legOf(legs, "MANUFACTURER").amount().compareTo(new BigDecimal("95.0000")));
    }

    @Test
    void legacyRateOneFallback_stillWorks_whenNoResidualBasis() {
        // V131 历史种子写法（basis=RATE, rate=1.0）→ 向后兼容，存量配置不改也能跑
        stubScene(
                rule("PLATFORM", RuleBasis.RATE, "0.05", null, null, 1, null),
                rule("MANUFACTURER", RuleBasis.RATE, "1.0", null, null, 99, null));

        List<SplitEngine.SplitLeg> legs = splitEngine.compute(SCENE, new BigDecimal("100"), null, "USD");

        assertEquals(0, sum(legs).compareTo(new BigDecimal("100")));
        assertEquals(0, legOf(legs, "MANUFACTURER").amount().compareTo(new BigDecimal("95.0000")));
    }

    @Test
    void duplicateResidualBasis_failsLoud() {
        // 两条 RESIDUAL：仅 priority 最大者作兜底，另一条按 basis 计算不了 → 必须显式报错，绝不静默错账
        stubScene(
                rule("PLATFORM", RuleBasis.RESIDUAL, null, null, null, 50, null),
                rule("MANUFACTURER", RuleBasis.RESIDUAL, null, null, null, 99, null));

        BizException ex = assertThrows(BizException.class,
                () -> splitEngine.compute(SCENE, new BigDecimal("100"), null, "USD"));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }
}
