package com.claw.server.domain.swap;

import com.claw.server.common.dto.SwapViews;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 换电计价服务单元测试：锁版价 0.12（光伏电费）+ 0.32（服务费）= 0.44/度。
 */
@ExtendWith(MockitoExtension.class)
class SwapBillingServiceTest {

    @Mock
    private ElecPriceSnapshotRepository elecRepo;
    @Mock
    private FeeRuleRepository feeRepo;
    @InjectMocks
    private SwapBillingService service;

    private ElecPriceSnapshot snapshot(BigDecimal pv) {
        ElecPriceSnapshot s = new ElecPriceSnapshot();
        s.setPvPrice(pv);
        s.setGridPrice(new BigDecimal("0.18"));
        return s;
    }

    private FeeRule feeRule(BigDecimal price) {
        FeeRule f = new FeeRule();
        f.setId(1L);
        f.setRuleCode("SWAP_SERVICE");
        f.setPrice(price);
        f.setShareJson("{\"battery_fund\":0.10,\"station\":0.19,\"platform\":0.03}");
        f.setStatus("ACTIVE");
        return f;
    }

    @BeforeEach
    void setup() {
        when(elecRepo.findFirstByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(any(LocalDate.class)))
                .thenReturn(Optional.of(snapshot(new BigDecimal("0.12"))));
        when(feeRepo.findByRuleCodeAndStatus("SWAP_SERVICE", "ACTIVE"))
                .thenReturn(Optional.of(feeRule(new BigDecimal("0.32"))));
    }

    @Test
    void quote_uses_locked_rates() {
        SwapViews.QuoteView q = service.quote(new BigDecimal("2.00"));

        assertEquals(0, q.elecRate().compareTo(new BigDecimal("0.12")));
        assertEquals(0, q.serviceRate().compareTo(new BigDecimal("0.32")));
        assertEquals(0, q.totalRate().compareTo(new BigDecimal("0.44")));
        assertEquals(0, q.elecFee().compareTo(new BigDecimal("0.24")));     // 2*0.12
        assertEquals(0, q.serviceFee().compareTo(new BigDecimal("0.64"))); // 2*0.32
        assertEquals(0, q.total().compareTo(new BigDecimal("0.88")));      // 约 0.92 量级
    }

    @Test
    void calc_with_given_rates_rounds_to_2_decimals() {
        SwapViews.QuoteView q = service.calc(new BigDecimal("1.55"),
                new BigDecimal("0.12"), new BigDecimal("0.32"));

        assertEquals(0, q.elecFee().compareTo(new BigDecimal("0.19")));     // 1.55*0.12=0.186→0.19
        assertEquals(0, q.serviceFee().compareTo(new BigDecimal("0.50")));  // 1.55*0.32=0.496→0.50
        assertEquals(0, q.total().compareTo(new BigDecimal("0.69")));
    }

    @Test
    void snapshot_json_contains_locked_rates_and_split() {
        String json = service.snapshotJson();
        assertTrue(json.contains("0.12"));
        assertTrue(json.contains("0.32"));
        assertTrue(json.contains("battery_fund"));
    }

    @Test
    void calc_by_snapshot_uses_snapshot_rates() {
        String snap = "{\"elecRate\":0.12,\"serviceRate\":0.32,\"serviceSplit\":{\"battery_fund\":0.10}}";
        SwapViews.QuoteView q = service.calcBySnapshot(snap, new BigDecimal("2.00"));
        assertEquals(0, q.total().compareTo(new BigDecimal("0.88")));
    }

    @Test
    void calc_by_snapshot_falls_back_to_current_when_invalid() {
        SwapViews.QuoteView q = service.calcBySnapshot("not-json", new BigDecimal("2.00"));
        assertEquals(0, q.total().compareTo(new BigDecimal("0.88")));
    }
}
