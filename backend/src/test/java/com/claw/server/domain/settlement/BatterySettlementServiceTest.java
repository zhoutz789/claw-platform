package com.claw.server.domain.settlement;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BatterySettlementService 单元测试（纯逻辑，不连 DB / 不加载 Spring）。
 * 验证：累计 Wh 净能量计量、ΔWh 电费、反补口径门控、损耗共担、服务费分成、币种默认。
 */
class BatterySettlementServiceTest {

    private final BatterySettlementService svc = new BatterySettlementService();
    private final BigDecimal PRICE = new BigDecimal("0.002"); // USD/Wh
    private final BigDecimal FEE = new BigDecimal("0.15");    // 服务费 USD（0.1–0.2 区间内）

    private BatterySettlementService.CustodyEpisode episode(BigDecimal takeC, BigDecimal takeD,
                                                              BigDecimal retC, BigDecimal retD,
                                                              BigDecimal metered) {
        return new BatterySettlementService.CustodyEpisode(1L, Instant.now().minusSeconds(3600), Instant.now(),
                new BatterySettlementService.CumulativeEnergy(takeC, takeD),
                new BatterySettlementService.CumulativeEnergy(retC, retD), metered);
    }

    @Test
    void consume_10000wh_bills_electricity_plus_service_fee() {
        var ep = episode(new BigDecimal("0"), new BigDecimal("0"),
                new BigDecimal("0"), new BigDecimal("10000"), null);
        var pricing = new BatterySettlementService.PricingContext(PRICE, FEE, "USD", true);

        var r = svc.settleSwap(ep, pricing);

        assertEquals(0, r.consumptionWh().compareTo(new BigDecimal("10000")));
        assertEquals(0, r.electricityFee().compareTo(new BigDecimal("20.0000")));
        assertEquals(0, r.serviceFee().compareTo(FEE));
        assertEquals(0, r.total().compareTo(new BigDecimal("20.1500")));
        assertEquals("INVESTOR_PLATFORM", r.lossBearer());
    }

    @Test
    void returned_more_only_metered_charge_is_compensated() {
        // 归还时净增 3000 Wh，但平台计量充电仅 0 → 不补偿（防套利）
        var ep = episode(new BigDecimal("0"), new BigDecimal("0"),
                new BigDecimal("3000"), new BigDecimal("0"), new BigDecimal("0"));
        var pricing = new BatterySettlementService.PricingContext(PRICE, FEE, "USD", true);
        var r = svc.settleSwap(ep, pricing);
        assertEquals(0, r.credit().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.total().compareTo(FEE)); // 仅服务费
    }

    @Test
    void returned_more_with_metered_charge_compensated() {
        // 净增 3000 Wh 且平台计量充电 3000 → 全额反补 6.00
        var ep = episode(new BigDecimal("0"), new BigDecimal("0"),
                new BigDecimal("3000"), new BigDecimal("0"), new BigDecimal("3000"));
        var pricing = new BatterySettlementService.PricingContext(PRICE, FEE, "USD", true);
        var r = svc.settleSwap(ep, pricing);
        assertEquals(0, r.credit().compareTo(new BigDecimal("6.0000")));
        assertEquals(0, r.total().compareTo(FEE.subtract(new BigDecimal("6.0000"))));
    }

    @Test
    void compensate_only_metered_false_compensates_full_credit() {
        var ep = episode(new BigDecimal("0"), new BigDecimal("0"),
                new BigDecimal("3000"), new BigDecimal("0"), new BigDecimal("0"));
        var pricing = new BatterySettlementService.PricingContext(PRICE, FEE, "USD", false);
        var r = svc.settleSwap(ep, pricing);
        assertEquals(0, r.credit().compareTo(new BigDecimal("6.0000")));
    }

    @Test
    void split_service_fee_follows_ratio() {
        var split = svc.splitServiceFee(FEE,
                new BatterySettlementService.RevenueSplit(
                        new BigDecimal("0.50"), new BigDecimal("0.15"), new BigDecimal("0.35")));
        assertEquals(0, split.investor().compareTo(new BigDecimal("0.0750")));
        assertEquals(0, split.station().compareTo(new BigDecimal("0.0225")));
        assertEquals(0, split.platform().compareTo(new BigDecimal("0.0525")));
    }

    @Test
    void currency_defaults_to_usd() {
        var ep = episode(new BigDecimal("0"), new BigDecimal("0"),
                new BigDecimal("0"), new BigDecimal("5000"), null);
        var pricing = new BatterySettlementService.PricingContext(PRICE, FEE, null, true);
        assertEquals("USD", svc.settleSwap(ep, pricing).currency());
        assertTrue(svc.settleSwap(ep, pricing).lines().size() >= 2);
    }
}
