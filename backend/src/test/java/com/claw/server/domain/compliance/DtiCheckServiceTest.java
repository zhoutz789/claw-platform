package com.claw.server.domain.compliance;

import com.claw.server.common.dto.ComplianceRequests;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DTI≤50% 强制校验单元测试（Mockito）。
 */
@ExtendWith(MockitoExtension.class)
class DtiCheckServiceTest {

    @Mock
    private ComplianceCheckRepository checkRepository;
    @InjectMocks
    private DtiCheckService service;

    private ComplianceRequests.DtiCheck req(BigDecimal debt, BigDecimal income) {
        return new ComplianceRequests.DtiCheck(100L, debt, income, null);
    }

    @Test
    void passes_when_dti_within_50_percent() {
        when(checkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.check(req(new BigDecimal("250.00"), new BigDecimal("1000.00")));

        assertEquals("PASS", view.result());
        assertEquals(0, view.dtiRate().compareTo(new BigDecimal("0.2500")));
    }

    @Test
    void rejects_when_dti_exceeds_50_percent() {
        when(checkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.check(req(new BigDecimal("600.00"), new BigDecimal("1000.00")));

        assertEquals("REJECT", view.result());
        assertEquals(0, view.dtiRate().compareTo(new BigDecimal("0.6000")));
    }

    @Test
    void boundary_50_percent_is_allowed() {
        when(checkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.check(req(new BigDecimal("500.00"), new BigDecimal("1000.00")));

        assertEquals("PASS", view.result());
    }

    @Test
    void rejects_zero_income() {
        assertThrows(Exception.class, () -> service.check(req(new BigDecimal("100.00"), BigDecimal.ZERO)));
        verify(checkRepository, never()).save(any());
    }
}
