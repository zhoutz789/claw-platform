package com.claw.server.domain.contract;

import com.claw.server.common.enums.ContractRefundStatus;
import com.claw.server.common.enums.ContractStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ContractService} 单元测试（不依赖 Spring / PG）。
 * 覆盖：① 激活签约生成 3 年期（effectiveTo = 签约 +3 年）；② 重试激活幂等（复用已有进行中合约）；
 * ③ 申请退出（EXIT_REQUESTED，refundDueAt = 申请 + 3 月）；④ 清算完成置 EXITED。
 */
class ContractServiceTest {

    private StationContractRepository contractRepository;
    private ContractService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        contractRepository = mock(StationContractRepository.class);
        service = new ContractService(contractRepository);
        // save 原样返回并补全 id
        when(contractRepository.save(any(StationContract.class))).thenAnswer(inv -> {
            StationContract c = inv.getArgument(0);
            if (c.getId() == null) c.setId(1L);
            return c;
        });
    }

    @Test
    @DisplayName("激活签约：3 年期，effectiveTo = 签约 +3 年，状态 ACTIVE")
    void createOnActivation_threeYearTerm() {
        when(contractRepository.findByStationIdAndStatusAndDeletedFalse(10L, ContractStatus.ACTIVE))
                .thenReturn(Optional.empty());

        StationContract c = service.createOnActivation(10L, 2L, new BigDecimal("20000.00"), new BigDecimal("80000.00"));

        assertNotNull(c.getContractNo());
        assertTrue(c.getContractNo().startsWith("CT"));
        assertEquals(ContractStatus.ACTIVE, c.getStatus());
        assertEquals(ContractRefundStatus.NONE, c.getRefundStatus());
        assertEquals(3, c.getTermYears());
        long years = ChronoUnit.DAYS.between(c.getEffectiveFrom(), c.getEffectiveTo());
        assertTrue(years >= 1090 && years <= 1100, "生效区间应≈3年，实际=" + years + "天");

        ArgumentCaptor<StationContract> cap = ArgumentCaptor.forClass(StationContract.class);
        verify(contractRepository).save(cap.capture());
        assertEquals(new BigDecimal("20000.00"), cap.getValue().getDepositAmount());
        assertEquals(new BigDecimal("80000.00"), cap.getValue().getCreditLimit());
    }

    @Test
    @DisplayName("重试激活幂等：已存在 ACTIVE 合约则复用，不再新建")
    void createOnActivation_idempotentOnRetry() {
        StationContract existing = StationContract.builder().id(99L).stationId(10L)
                .status(ContractStatus.ACTIVE).build();
        when(contractRepository.findByStationIdAndStatusAndDeletedFalse(10L, ContractStatus.ACTIVE))
                .thenReturn(Optional.of(existing));

        StationContract c = service.createOnActivation(10L, 2L, new BigDecimal("20000.00"), new BigDecimal("80000.00"));

        assertEquals(99L, c.getId());
        verify(contractRepository, never()).save(any(StationContract.class));
    }

    @Test
    @DisplayName("申请退出：EXIT_REQUESTED，refundDueAt = 申请 + 3 月")
    void requestExit_setsRefundWindow() {
        StationContract c = StationContract.builder().id(1L).stationId(10L).status(ContractStatus.ACTIVE).build();
        when(contractRepository.findById(1L)).thenReturn(Optional.of(c));

        StationContract exited = service.requestExit(1L, 7L, "合约到期退出");

        assertEquals(ContractStatus.EXIT_REQUESTED, exited.getStatus());
        assertEquals(ContractRefundStatus.PENDING, exited.getRefundStatus());
        assertNotNull(exited.getRefundDueAt());
        long days = ChronoUnit.DAYS.between(exited.getExitRequestedAt(), exited.getRefundDueAt());
        assertTrue(days >= 85 && days <= 95, "退款窗口应≈90天，实际=" + days);

        ArgumentCaptor<StationContract> cap = ArgumentCaptor.forClass(StationContract.class);
        verify(contractRepository).save(cap.capture());
        assertEquals("合约到期退出", cap.getValue().getRemark());
    }

    @Test
    @DisplayName("已 EXITED 合约不可再申请退出")
    void requestExit_rejectsAlreadyExited() {
        StationContract c = StationContract.builder().id(1L).stationId(10L).status(ContractStatus.EXITED).build();
        when(contractRepository.findById(1L)).thenReturn(Optional.of(c));

        assertThrows(IllegalStateException.class, () -> service.requestExit(1L, 7L, "重复退出"));
    }

    @Test
    @DisplayName("保证金清算完成：置 EXITED，记录实退额")
    void markRefunded_fullThenDeducted() {
        StationContract c = StationContract.builder().id(1L).stationId(10L).status(ContractStatus.EXIT_REQUESTED).build();
        when(contractRepository.findById(1L)).thenReturn(Optional.of(c));

        StationContract done = service.markRefunded(1L, true, new BigDecimal("20000.00"), 7L);

        assertEquals(ContractStatus.EXITED, done.getStatus());
        assertEquals(ContractRefundStatus.REFUNDED, done.getRefundStatus());
        assertEquals(0, done.getRefundAmount().compareTo(new BigDecimal("20000.00")));
        assertNotNull(done.getTerminatedAt());
    }
}
