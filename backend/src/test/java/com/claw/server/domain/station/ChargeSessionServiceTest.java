package com.claw.server.domain.station;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ChargeSessionService 计费单测（纯逻辑 computeFees，不连 DB / 不加载 Spring）。
 */
class ChargeSessionServiceTest {

    private static final BigDecimal PRICE = new BigDecimal("0.002"); // USD/Wh
    private static final BigDecimal FEE = new BigDecimal("0.15");    // 统一服务费

    @Test
    void fees_equal_energy_times_price_plus_service_fee() {
        var f = ChargeSessionService.computeFees(new BigDecimal("10000"), PRICE, FEE);
        assertEquals(0, f.electricityFee().compareTo(new BigDecimal("20.0000")));
        assertEquals(0, f.serviceFee().compareTo(FEE));
        assertEquals(0, f.total().compareTo(new BigDecimal("20.1500")));
    }

    @Test
    void null_inputs_treated_as_zero() {
        var f = ChargeSessionService.computeFees(null, null, null);
        assertEquals(0, f.total().compareTo(BigDecimal.ZERO));
        assertEquals(0, f.electricityFee().compareTo(BigDecimal.ZERO));
    }

    @Test
    void zero_energy_still_charges_service_fee() {
        var f = ChargeSessionService.computeFees(BigDecimal.ZERO, PRICE, FEE);
        assertEquals(0, f.electricityFee().compareTo(BigDecimal.ZERO));
        assertEquals(0, f.total().compareTo(FEE));
    }
}
