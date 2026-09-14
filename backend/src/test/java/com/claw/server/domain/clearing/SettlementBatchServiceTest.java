package com.claw.server.domain.clearing;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.BatchStatus;
import com.claw.server.common.enums.ClearingScene;
import com.claw.server.common.enums.ClearingStatus;
import com.claw.server.domain.payment.ClearingChannelGateway;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 结算批次服务单测：T+7 due_date 计算、批次状态机、PARTIAL 补发、终态不可转移、collect 幂等。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettlementBatchServiceTest {

    @Mock private ClearingInstructionRepository clearingInstructionRepository;
    @Mock private SettlementBatchRepository settlementBatchRepository;
    @Mock private SettlementBatchItemRepository settlementBatchItemRepository;
    @Mock private ClearingChannelGateway clearingChannelGateway;
    @Mock private SystemConfigRepository systemConfigRepository;
    @InjectMocks private SettlementBatchService service;

    private final Instant periodStart = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant periodEnd = Instant.parse("2026-01-31T23:59:59Z");

    @BeforeEach
    void setUp() {
        when(settlementBatchRepository.save(any(SettlementBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(settlementBatchItemRepository.saveAll(anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(clearingChannelGateway.payoutToPayee(any(), any(), any(), any())).thenReturn("REF-MOCK");
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse("SETTLE_CYCLE_GOODS"))
                .thenReturn(Optional.of(SystemConfig.builder().configKey("SETTLE_CYCLE_GOODS")
                        .configValue("T+7").build()));
    }

    @Test
    @DisplayName("collect：货款场景 T+7 应付日 + 明细与总额正确")
    void collect_computes_T7_dueDate_and_items() {
        ClearingInstruction i1 = instruction(10L, new BigDecimal("10.00"), Instant.parse("2026-01-15T00:00:00Z"));
        ClearingInstruction i2 = instruction(11L, new BigDecimal("20.00"), Instant.parse("2026-01-20T00:00:00Z"));
        when(clearingInstructionRepository.findBySceneAndStatusOrderByCreatedAtAsc(ClearingScene.R1, ClearingStatus.CREATED))
                .thenReturn(List.of(i1, i2));
        when(settlementBatchRepository.findByBizSceneAndPeriodStartAndPeriodEndAndDeletedFalse(
                eq(ClearingScene.R1.name()), eq(periodStart), eq(periodEnd)))
                .thenReturn(Optional.empty());

        SettlementBatch batch = service.collect(ClearingScene.R1, periodStart, periodEnd);

        assertEquals(BatchStatus.COLLECTING, batch.getStatus());
        assertEquals(LocalDate.of(2026, 2, 7), batch.getDueDate()); // 1/31 + 7d
        assertEquals(2, batch.getItemCount());
        assertEquals(0, new BigDecimal("30.00").compareTo(batch.getTotalAmount()));
        verify(settlementBatchItemRepository).saveAll(argThat(items ->
                java.util.stream.StreamSupport.stream(items.spliterator(), false).count() == 2));
    }

    @Test
    @DisplayName("collect：同周期重复调用返回既有批次，不再落库")
    void collect_idempotent_same_period() {
        SettlementBatch existing = SettlementBatch.builder().id(99L).batchNo("SB-EXIST")
                .bizScene(ClearingScene.R1.name()).status(BatchStatus.COLLECTING).build();
        when(settlementBatchRepository.findByBizSceneAndPeriodStartAndPeriodEndAndDeletedFalse(
                eq(ClearingScene.R1.name()), eq(periodStart), eq(periodEnd)))
                .thenReturn(Optional.of(existing));

        SettlementBatch result = service.collect(ClearingScene.R1, periodStart, periodEnd);

        assertSame(existing, result);
        verify(settlementBatchRepository, never()).save(any(SettlementBatch.class));
    }

    @Test
    @DisplayName("approve：COLLECTING → REVIEWING → APPROVED")
    void approve_transitions_collecting_to_approved() {
        SettlementBatch batch = batch(BatchStatus.COLLECTING);
        when(settlementBatchRepository.findById(1L)).thenReturn(Optional.of(batch));

        SettlementBatch reviewing = service.approve(1L, 7L);
        assertEquals(BatchStatus.REVIEWING, reviewing.getStatus());

        SettlementBatch approved = service.approve(1L, 7L);
        assertEquals(BatchStatus.APPROVED, approved.getStatus());
        assertEquals(7L, approved.getApprovedBy());
        assertNotNull(approved.getApprovedAt());
    }

    @Test
    @DisplayName("PARTIAL 补发：部分回执 → PARTIAL → 补发 → 全部回执 → SETTLED")
    void partial_resend_then_settled() {
        SettlementBatch batch = batch(BatchStatus.SENDING);
        SettlementBatchItem item0 = item(1L, 10L, "PENDING");
        SettlementBatchItem item1 = item(2L, 11L, "PENDING");
        when(settlementBatchRepository.findById(1L)).thenReturn(Optional.of(batch));
        when(settlementBatchItemRepository.findByBatchId(1L)).thenReturn(List.of(item0, item1));
        when(clearingInstructionRepository.findById(10L)).thenReturn(Optional.of(instruction(10L, BigDecimal.ONE, null)));
        when(clearingInstructionRepository.findById(11L)).thenReturn(Optional.of(instruction(11L, BigDecimal.ONE, null)));

        // 仅 1 条回执 → item0 SETTLED、item1 FAILED、批次 PARTIAL
        SettlementBatch partial = service.onBatchAck(1L, List.of("REF1"));
        assertEquals(BatchStatus.PARTIAL, partial.getStatus());
        assertEquals("SETTLED", item0.getStatus());
        assertEquals("FAILED", item1.getStatus());

        // 再次下发（仅失败项补发）+ 全部回执 → SETTLED
        SettlementBatch resending = service.submit(1L);
        assertEquals(BatchStatus.SENDING, resending.getStatus());
        assertEquals("SENT", item1.getStatus());

        SettlementBatch settled = service.onBatchAck(1L, List.of("REF1", "REF2"));
        assertEquals(BatchStatus.SETTLED, settled.getStatus());
        assertEquals("SETTLED", item1.getStatus());
        assertNotNull(settled.getSettledAt());
    }

    @Test
    @DisplayName("终态不可转移：SETTLED 批次再 submit 抛非法状态")
    void terminal_state_rejects_transition() {
        SettlementBatch batch = batch(BatchStatus.SETTLED);
        when(settlementBatchRepository.findById(1L)).thenReturn(Optional.of(batch));

        BizException ex = assertThrows(BizException.class, () -> service.submit(1L));
        assertTrue(ex.getMessage().contains("error.clearing.batch.illegal.transition"));
    }

    private SettlementBatch batch(BatchStatus status) {
        return SettlementBatch.builder().id(1L).batchNo("SB-TEST").bizScene(ClearingScene.R1.name())
                .status(status).build();
    }

    private SettlementBatchItem item(Long ciId, Long vsaId, String status) {
        return SettlementBatchItem.builder().id(ciId * 100 + vsaId).batchId(1L)
                .clearingInstructionId(ciId).payeeVsaId(vsaId).payeeAccountId(vsaId + 1000L)
                .amount(BigDecimal.TEN).currency("USD").status(status).build();
    }

    private ClearingInstruction instruction(Long id, BigDecimal amount, Instant createdAt) {
        return ClearingInstruction.builder().id(id).instructionNo("CI-" + id).amount(amount)
                .currency("USD").createdAt(createdAt).payeeVsaId(id.intValue() * 10L)
                .payeeAccountId(id.intValue() * 100L).scene(ClearingScene.R1)
                .status(ClearingStatus.CREATED).build();
    }
}
