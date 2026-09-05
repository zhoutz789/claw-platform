package com.claw.server.domain.compliance;

import com.claw.server.common.api.BizException;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ComplianceTextServiceTest {

    private SystemConfigRepository repo;
    private ComplianceTextService service;

    @BeforeEach
    void init() {
        repo = org.mockito.Mockito.mock(SystemConfigRepository.class);
        service = new ComplianceTextService(repo);
    }

    @Test
    void scan_rejectsProhibitedKeyword() {
        ComplianceTextService.TextCheckResult r = service.scan("本项目保本稳赚，零风险高收益");
        assertFalse(r.allowed());
        assertEquals("REJECT", r.hits().get(0).severity());
    }

    @Test
    void scan_passesCleanText() {
        ComplianceTextService.TextCheckResult r = service.scan("本产能计划按真实租赁绩效计提回佣，不保底不保息");
        assertTrue(r.allowed());
        assertTrue(r.hits().isEmpty());
    }

    @Test
    void scan_usesConfigWhenPresent() {
        SystemConfig cfg = SystemConfig.builder()
                .configKey("COMPLIANCE_PROHIBITED_KEYWORDS")
                .configValue("[{\"pattern\":\"FORBIDDEN_XYZ\",\"severity\":\"REJECT\",\"category\":\"TEST\"}]")
                .build();
        when(repo.findByConfigKeyAndDeletedFalse("COMPLIANCE_PROHIBITED_KEYWORDS")).thenReturn(Optional.of(cfg));
        ComplianceTextService.TextCheckResult r = service.scan("contains FORBIDDEN_XYZ here");
        assertFalse(r.allowed());
        assertEquals(1, r.hits().size());
    }

    @Test
    void scan_emptyTextAllowed() {
        ComplianceTextService.TextCheckResult r = service.scan("   ");
        assertTrue(r.allowed());
    }

    @Test
    void assertAllowed_throwsOnReject() {
        assertThrows(BizException.class, () -> service.assertAllowed("保本理财", "CAPACITY_PLAN"));
    }

    @Test
    void assertAllowed_passesClean() {
        assertDoesNotThrow(() -> service.assertAllowed("正常文案", "CAPACITY_PLAN"));
    }
}
