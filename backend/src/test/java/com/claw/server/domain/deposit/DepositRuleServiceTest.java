package com.claw.server.domain.deposit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DepositRuleServiceTest {

    private DepositRuleRepository repo;
    private DepositRuleService service;

    @BeforeEach
    void init() {
        repo = mock(DepositRuleRepository.class);
        service = new DepositRuleService(repo);
    }

    private DepositRule rule(String assetType, int yearIndex, BigDecimal rate) {
        return DepositRule.builder()
                .assetType(assetType)
                .yearIndex(yearIndex)
                .depositRate(rate)
                .build();
    }

    /** 资产类型为 null 时直接回退默认费率 0.30。 */
    @Test
    void computeDepositRate_nullAssetType_returnsDefault() {
        BigDecimal rate = service.computeDepositRate(null, 1);
        assertEquals(0, rate.compareTo(new BigDecimal("0.30")));
        verify(repo, never()).findByAssetTypeAndYearIndex(any(), anyInt());
    }

    /** 命中阶梯规则时返回该年对应费率。 */
    @Test
    void computeDepositRate_ruleFound_returnsRuleRate() {
        when(repo.findByAssetTypeAndYearIndex("VEHICLE", 2))
                .thenReturn(Optional.of(rule("VEHICLE", 2, new BigDecimal("0.25"))));

        BigDecimal rate = service.computeDepositRate("VEHICLE", 2);
        assertEquals(0, rate.compareTo(new BigDecimal("0.25")));
    }

    /** yearIndex <= 0 视作首年（idx 归一为 1）。 */
    @Test
    void computeDepositRate_yearIndexNormalizedToFirstYear() {
        when(repo.findByAssetTypeAndYearIndex("BATTERY", 1))
                .thenReturn(Optional.of(rule("BATTERY", 1, new BigDecimal("0.30"))));

        BigDecimal rateZero = service.computeDepositRate("BATTERY", 0);
        BigDecimal rateNeg = service.computeDepositRate("BATTERY", -3);
        assertEquals(0, rateZero.compareTo(new BigDecimal("0.30")));
        assertEquals(0, rateNeg.compareTo(new BigDecimal("0.30")));
    }

    /** 查不到规则时回退默认费率 0.30。 */
    @Test
    void computeDepositRate_notFound_returnsDefault() {
        when(repo.findByAssetTypeAndYearIndex("DRONE", 3)).thenReturn(Optional.empty());

        BigDecimal rate = service.computeDepositRate("DRONE", 3);
        assertEquals(0, rate.compareTo(new BigDecimal("0.30")));
    }

    /** 总额 null 时押金金额按 0 计算。 */
    @Test
    void computeDepositAmount_nullTotal_returnsZero() {
        BigDecimal amount = service.computeDepositAmount(null, "VEHICLE", 1);
        assertEquals(0, amount.compareTo(BigDecimal.ZERO));
    }

    /** 押金金额 = 总额 × 对应年率。 */
    @Test
    void computeDepositAmount_totalTimesRate() {
        when(repo.findByAssetTypeAndYearIndex("VEHICLE", 1))
                .thenReturn(Optional.of(rule("VEHICLE", 1, new BigDecimal("0.30"))));

        BigDecimal amount = service.computeDepositAmount(new BigDecimal("10000.00"), "VEHICLE", 1);
        assertEquals(0, amount.compareTo(new BigDecimal("3000.0000")));
    }
}
