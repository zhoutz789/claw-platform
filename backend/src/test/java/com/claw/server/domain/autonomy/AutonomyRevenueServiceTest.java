package com.claw.server.domain.autonomy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AutonomyRevenueServiceTest {

    @InjectMocks
    private AutonomyRevenueService service;

    @Test
    void computeSplit_splitsCorrectly() {
        Map<String, BigDecimal> split = service.computeSplit(
                new BigDecimal("100"),
                new BigDecimal("0.6"), new BigDecimal("0.3"), new BigDecimal("0.1"));

        assertEquals(new BigDecimal("60.00"), split.get("OWNER"));
        assertEquals(new BigDecimal("30.00"), split.get("PLATFORM"));
        assertEquals(new BigDecimal("10.00"), split.get("ALGO"));
        BigDecimal sum = split.get("OWNER").add(split.get("PLATFORM")).add(split.get("ALGO"));
        assertEquals(new BigDecimal("100.00"), sum);
    }

    @Test
    void splitForTask_usesDefaultRatios() {
        Map<String, BigDecimal> split = service.splitForTask(1L, new BigDecimal("100"));

        assertEquals(new BigDecimal("60.00"), split.get("OWNER"));
        assertEquals(new BigDecimal("30.00"), split.get("PLATFORM"));
        assertEquals(new BigDecimal("10.00"), split.get("ALGO"));
    }

    @Test
    void computeSplit_throwsOnInvalidRatio() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.computeSplit(new BigDecimal("100"),
                        new BigDecimal("0.6"), new BigDecimal("0.4"), new BigDecimal("0.1")));
        assertTrue(ex.getMessage().contains("autonomy.split.ratio.invalid"));
    }
}
