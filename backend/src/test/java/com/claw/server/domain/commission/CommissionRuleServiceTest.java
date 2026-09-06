package com.claw.server.domain.commission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommissionRuleServiceTest {

    private CommissionRuleRepository repo;
    private CommissionRuleService service;

    @BeforeEach
    void init() {
        repo = mock(CommissionRuleRepository.class);
        service = new CommissionRuleService(repo);
    }

    private CommissionRule rule(String type, BigDecimal rate, BigDecimal amount,
                                BigDecimal min, BigDecimal max, int priority) {
        CommissionRule r = new CommissionRule();
        r.setCommissionType(type);
        r.setRate(rate);
        r.setAmount(amount);
        r.setMinAmount(min);
        r.setMaxAmount(max);
        r.setPriority(priority);
        return r;
    }

    /** 厂家+商品专属 RATE 规则优先命中：commission = sale * rate。 */
    @Test
    void resolveCommission_manufacturerProductRate() {
        CommissionRule productRule = rule("RATE", new BigDecimal("0.10"), null, null, null, 5);
        when(repo.findByManufacturerIdAndProductIdAndEnabledTrue(10L, 20L))
                .thenReturn(Optional.of(productRule));

        BigDecimal c = service.resolveCommission(10L, 20L, new BigDecimal("1000.00"));

        assertEquals(0, c.compareTo(new BigDecimal("100.00")));
        verify(repo, never()).findByManufacturerIdAndEnabledTrue(anyLong());
    }

    /** 无商品规则时回退厂家级规则，取 priority 最大者。 */
    @Test
    void resolveCommission_fallsBackToManufacturerMaxPriority() {
        when(repo.findByManufacturerIdAndProductIdAndEnabledTrue(10L, 20L))
                .thenReturn(Optional.empty());
        CommissionRule low = rule("RATE", new BigDecimal("0.05"), null, null, null, 1);
        CommissionRule high = rule("RATE", new BigDecimal("0.08"), null, null, null, 9);
        when(repo.findByManufacturerIdAndEnabledTrue(10L)).thenReturn(List.of(low, high));

        BigDecimal c = service.resolveCommission(10L, 20L, new BigDecimal("1000.00"));

        assertEquals(0, c.compareTo(new BigDecimal("80.00")));
    }

    /** 厂家级无规则时回退平台默认（manufacturer_id IS NULL）。 */
    @Test
    void resolveCommission_fallsBackToPlatformDefault() {
        when(repo.findByManufacturerIdAndProductIdAndEnabledTrue(10L, 20L))
                .thenReturn(Optional.empty());
        when(repo.findByManufacturerIdAndEnabledTrue(10L)).thenReturn(List.of());
        CommissionRule def = rule("AMOUNT", null, new BigDecimal("50.00"), null, null, 3);
        when(repo.findByManufacturerIdIsNullAndEnabledTrue()).thenReturn(List.of(def));

        BigDecimal c = service.resolveCommission(10L, 20L, new BigDecimal("9999.00"));

        assertEquals(0, c.compareTo(new BigDecimal("50.00")));
    }

    /** AMOUNT 定额规则直接返回定金额。 */
    @Test
    void resolveCommission_amountRule() {
        CommissionRule amt = rule("AMOUNT", null, new BigDecimal("30.00"), null, null, 2);
        when(repo.findByManufacturerIdAndProductIdAndEnabledTrue(anyLong(), anyLong()))
                .thenReturn(Optional.of(amt));

        BigDecimal c = service.resolveCommission(10L, 20L, new BigDecimal("1000.00"));

        assertEquals(0, c.compareTo(new BigDecimal("30.00")));
    }

    /** min/max 封顶保底：计算结果低于 min 取 min，高于 max 取 max。 */
    @Test
    void resolveCommission_clampsMinAndMax() {
        CommissionRule clamped = rule("RATE", new BigDecimal("0.10"), null,
                new BigDecimal("20.00"), new BigDecimal("50.00"), 1);
        when(repo.findByManufacturerIdAndProductIdAndEnabledTrue(anyLong(), anyLong()))
                .thenReturn(Optional.of(clamped));

        // 100*0.10=10 < min 20 -> 取 20
        assertEquals(0, service.resolveCommission(1L, 2L, new BigDecimal("100.00"))
                .compareTo(new BigDecimal("20.00")));
        // 1000*0.10=100 > max 50 -> 取 50
        assertEquals(0, service.resolveCommission(1L, 2L, new BigDecimal("1000.00"))
                .compareTo(new BigDecimal("50.00")));
    }

    /** 任何规则都不命中返回 ZERO，且不为负。 */
    @Test
    void resolveCommission_noRuleReturnsZero() {
        when(repo.findByManufacturerIdAndProductIdAndEnabledTrue(anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        when(repo.findByManufacturerIdAndEnabledTrue(anyLong())).thenReturn(List.of());
        when(repo.findByManufacturerIdIsNullAndEnabledTrue()).thenReturn(List.of());

        BigDecimal c = service.resolveCommission(10L, 20L, new BigDecimal("1000.00"));

        assertEquals(0, c.compareTo(BigDecimal.ZERO));
    }

    /** createRule 缺省 enabled=true、priority=0。 */
    @Test
    void createRule_appliesDefaults() {
        CommissionRule input = new CommissionRule();
        when(repo.save(any(CommissionRule.class))).thenAnswer(inv -> inv.getArgument(0));

        CommissionRule saved = service.createRule(input);

        assertTrue(saved.getEnabled());
        assertEquals(0, saved.getPriority());
        assertNotNull(saved.getCreatedAt());
    }
}
