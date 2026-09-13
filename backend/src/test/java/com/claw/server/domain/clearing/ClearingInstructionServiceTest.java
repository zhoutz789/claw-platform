package com.claw.server.domain.clearing;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.ClearingMode;
import com.claw.server.common.enums.ClearingScene;
import com.claw.server.common.enums.ClearingStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link ClearingInstructionService} 单元测试（Mockito，无 Docker/PG）。
 * 覆盖：创建 + 幂等、状态机全转移、非法转移拒绝、重试上限、终态锁定。
 */
@ExtendWith(MockitoExtension.class)
class ClearingInstructionServiceTest {

    @Mock
    private ClearingInstructionRepository clearingInstructionRepository;

    @InjectMocks
    private ClearingInstructionService service;

    private static ClearingInstruction instruction(Long id, ClearingStatus status, Integer retry) {
        return ClearingInstruction.builder()
                .id(id).status(status).retryCount(retry)
                .scene(ClearingScene.R1).mode(ClearingMode.AT_SOURCE)
                .instructionNo("CI-R1-1-AAAAAA").idemKey("R1:ORD-1:PLATFORM")
                .amount(new BigDecimal("5.0000")).currency("USD")
                .ledgerBizType("CLEARING_SETTLE").ledgerBizRef("ORD-1:PLATFORM")
                .deleted(false)
                .build();
    }

    private static SplitEngine.SplitLeg leg(String payee, String amount) {
        return new SplitEngine.SplitLeg(payee, new BigDecimal(amount), "T+0", 1, 9L);
    }

    // ===================== create =====================

    @Test
    void create_new_buildsCreatedInstruction() {
        String idemKey = ClearingInstructionService.idemKey(ClearingScene.R1, "ORD-1", "PLATFORM");
        when(clearingInstructionRepository.findByIdemKey(idemKey)).thenReturn(Optional.empty());
        when(clearingInstructionRepository.save(any(ClearingInstruction.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ClearingInstruction ci = service.create(ClearingScene.R1, ClearingMode.AT_SOURCE,
                leg("PLATFORM", "5.0000"), "ORD-1", "USD");

        assertEquals(ClearingStatus.CREATED, ci.getStatus());
        assertEquals(idemKey, ci.getIdemKey());
        assertTrue(ci.getInstructionNo().startsWith("CI-R1-"));
        assertEquals("ORD-1:PLATFORM", ci.getLedgerBizRef());
        assertEquals("CLEARING_SETTLE", ci.getLedgerBizType());
        assertEquals(0, ci.getRetryCount());
        verify(clearingInstructionRepository).save(any(ClearingInstruction.class));
    }

    @Test
    void create_idemKeyExists_returnsExistingWithoutSave() {
        String idemKey = ClearingInstructionService.idemKey(ClearingScene.R1, "ORD-1", "PLATFORM");
        ClearingInstruction existing = instruction(7L, ClearingStatus.CREATED, 0);
        when(clearingInstructionRepository.findByIdemKey(idemKey)).thenReturn(Optional.of(existing));

        ClearingInstruction ci = service.create(ClearingScene.R1, ClearingMode.AT_SOURCE,
                leg("PLATFORM", "5.0000"), "ORD-1", "USD");

        assertSame(existing, ci);
        verify(clearingInstructionRepository, never()).save(any());
    }

    // ===================== 状态机 =====================

    @Test
    void markSent_createdToSent() {
        ClearingInstruction ci = instruction(1L, ClearingStatus.CREATED, 0);
        when(clearingInstructionRepository.findById(1L)).thenReturn(Optional.of(ci));
        when(clearingInstructionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClearingInstruction result = service.markSent(1L, "ABA_PAYWAY");

        assertEquals(ClearingStatus.SENT, result.getStatus());
        assertEquals("ABA_PAYWAY", result.getChannel());
        assertNotNull(result.getSentAt());
    }

    @Test
    void markSent_fromSent_throwsIllegal() {
        ClearingInstruction ci = instruction(1L, ClearingStatus.SENT, 0);
        when(clearingInstructionRepository.findById(1L)).thenReturn(Optional.of(ci));

        BizException ex = assertThrows(BizException.class, () -> service.markSent(1L, "ABA"));
        assertEquals(40903, ex.getCode());
        verify(clearingInstructionRepository, never()).save(any());
    }

    @Test
    void onAck_sentToAcked() {
        ClearingInstruction ci = instruction(1L, ClearingStatus.SENT, 0);
        when(clearingInstructionRepository.findById(1L)).thenReturn(Optional.of(ci));
        when(clearingInstructionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClearingInstruction result = service.onAck(1L, "INST-REF-1");

        assertEquals(ClearingStatus.ACKED, result.getStatus());
        assertEquals("INST-REF-1", result.getInstitutionRef());
        assertNotNull(result.getAckedAt());
    }

    @Test
    void markSettled_ackedToSettled() {
        ClearingInstruction ci = instruction(1L, ClearingStatus.ACKED, 0);
        when(clearingInstructionRepository.findById(1L)).thenReturn(Optional.of(ci));
        when(clearingInstructionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClearingInstruction result = service.markSettled(1L);

        assertEquals(ClearingStatus.SETTLED, result.getStatus());
        assertNotNull(result.getSettledAt());
    }

    @Test
    void markSettled_fromSent_throwsIllegal() {
        ClearingInstruction ci = instruction(1L, ClearingStatus.SENT, 0);
        when(clearingInstructionRepository.findById(1L)).thenReturn(Optional.of(ci));

        assertThrows(BizException.class, () -> service.markSettled(1L));
    }

    @Test
    void terminalStatus_cannotTransition() {
        ClearingInstruction settled = instruction(1L, ClearingStatus.SETTLED, 0);
        when(clearingInstructionRepository.findById(1L)).thenReturn(Optional.of(settled));

        BizException ex = assertThrows(BizException.class, () -> service.markSent(1L, "ABA"));
        assertEquals(40903, ex.getCode());
        verify(clearingInstructionRepository, never()).save(any());
    }

    @Test
    void markFailed_sentToFailed() {
        ClearingInstruction ci = instruction(1L, ClearingStatus.SENT, 0);
        when(clearingInstructionRepository.findById(1L)).thenReturn(Optional.of(ci));
        when(clearingInstructionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClearingInstruction result = service.markFailed(1L, "通道超时");

        assertEquals(ClearingStatus.FAILED, result.getStatus());
        assertEquals("通道超时", result.getFailReason());
    }

    // ===================== retry =====================

    @Test
    void retry_failedToSent_incrementsCount() {
        ClearingInstruction ci = instruction(1L, ClearingStatus.FAILED, 0);
        when(clearingInstructionRepository.findById(1L)).thenReturn(Optional.of(ci));
        when(clearingInstructionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClearingInstruction result = service.retry(1L);

        assertEquals(ClearingStatus.SENT, result.getStatus());
        assertEquals(1, result.getRetryCount());
    }

    @Test
    void retry_exceedsLimit_escalatesToManual() {
        ClearingInstruction ci = instruction(1L, ClearingStatus.FAILED, ClearingInstructionService.MAX_RETRY);
        when(clearingInstructionRepository.findById(1L)).thenReturn(Optional.of(ci));
        when(clearingInstructionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClearingInstruction result = service.retry(1L);

        assertEquals(ClearingStatus.MANUAL, result.getStatus());
        assertEquals(ClearingInstructionService.MAX_RETRY + 1, result.getRetryCount());
    }

    @Test
    void retry_fromCreated_throwsIllegal() {
        ClearingInstruction ci = instruction(1L, ClearingStatus.CREATED, 0);
        when(clearingInstructionRepository.findById(1L)).thenReturn(Optional.of(ci));

        assertThrows(BizException.class, () -> service.retry(1L));
    }

    // ===================== escalate / 查询 =====================

    @Test
    void escalateManual_fromCreated_setsManual() {
        ClearingInstruction ci = instruction(1L, ClearingStatus.CREATED, 0);
        when(clearingInstructionRepository.findById(1L)).thenReturn(Optional.of(ci));
        when(clearingInstructionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClearingInstruction result = service.escalateManual(1L, "需人工核查");

        assertEquals(ClearingStatus.MANUAL, result.getStatus());
    }

    @Test
    void escalateManual_fromTerminal_throwsIllegal() {
        ClearingInstruction ci = instruction(1L, ClearingStatus.SETTLED, 0);
        when(clearingInstructionRepository.findById(1L)).thenReturn(Optional.of(ci));

        assertThrows(BizException.class, () -> service.escalateManual(1L, "x"));
    }

    @Test
    void get_notFound_throws() {
        when(clearingInstructionRepository.findById(99L)).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class, () -> service.get(99L));
        assertEquals(BizException.NOT_FOUND, ex.getCode());
    }

    @Test
    void findByIdemKey_blank_returnsEmptyWithoutQuery() {
        assertTrue(service.findByIdemKey("  ").isEmpty());
        verify(clearingInstructionRepository, never()).findByIdemKey(anyString());
    }
}
