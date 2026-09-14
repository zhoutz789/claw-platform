package com.claw.server.domain.payment;

import com.claw.server.common.dto.PaymentViews;
import com.claw.server.common.enums.WalletTxnStatus;
import com.claw.server.domain.clearing.SuspenseEntryRepository;
import com.claw.server.domain.clearing.SuspenseService;
import com.claw.server.domain.funds.FundsLocationService;
import com.claw.server.domain.ledger.AccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 日终对账单元测试：平台净额 vs ABA 银行净额，一致 MATCHED / 差异 MISMATCH。
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock private WalletTxnRepository walletTxnRepository;
    @Mock private ReconciliationRunRepository reconciliationRunRepository;
    @Mock private AbaGateway abaGateway;
    @Mock private AccountService accountService;
    @Mock private FundsLocationService fundsLocationService;
    @Mock private SuspenseService suspenseService;
    @Mock private SuspenseEntryRepository suspenseEntryRepository;
    @Mock private CustodyBalanceFeed custodyBalanceFeed;
    @InjectMocks private ReconciliationService service;

    private WalletTxn txn(String type, WalletTxnStatus status, String amount) {
        return WalletTxn.builder().txnNo("T").userId(1L).txnType(type)
                .amountUsd(new BigDecimal(amount)).status(status).build();
    }

    private void stubSave() {
        when(reconciliationRunRepository.findByRunDate(any(LocalDate.class))).thenReturn(Optional.empty());
        when(reconciliationRunRepository.save(any(ReconciliationRun.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void runDaily_marks_matched_when_net_equal() {
        when(walletTxnRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of(
                txn("RECHARGE", WalletTxnStatus.PAID, "100.00"),
                txn("WITHDRAW", WalletTxnStatus.SUCCESS, "30.00")));
        when(abaGateway.fetchDailyNetAmount(any(LocalDate.class))).thenReturn(new BigDecimal("70.00"));
        stubSave();

        PaymentViews.ReconciliationView v = service.runDaily(LocalDate.of(2026, 8, 20));

        assertEquals("MATCHED", v.status());
        assertEquals(0, v.mismatchCount());
        assertEquals(0, new BigDecimal("70.00").compareTo(v.platformTotal()));
    }

    @Test
    void runDaily_marks_mismatch_when_net_differs() {
        when(walletTxnRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of(
                txn("RECHARGE", WalletTxnStatus.PAID, "100.00")));
        when(abaGateway.fetchDailyNetAmount(any(LocalDate.class))).thenReturn(new BigDecimal("95.00"));
        stubSave();

        PaymentViews.ReconciliationView v = service.runDaily(LocalDate.of(2026, 8, 20));

        assertEquals("MISMATCH", v.status());
        assertEquals(1, v.mismatchCount());
        assertNotNull(v.detailJson());
    }

    @Test
    void runDaily_ignores_failed_withdraw_in_net() {
        // 提现失败退回：净额应为 0 贡献
        when(walletTxnRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of(
                txn("WITHDRAW", WalletTxnStatus.FAILED_REFUND, "30.00")));
        when(abaGateway.fetchDailyNetAmount(any(LocalDate.class))).thenReturn(BigDecimal.ZERO);
        stubSave();

        PaymentViews.ReconciliationView v = service.runDaily(LocalDate.of(2026, 8, 20));

        assertEquals("MATCHED", v.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(v.platformTotal()));
    }
}
