package com.claw.server.domain.payment;

import com.claw.server.common.dto.PaymentRequests;
import com.claw.server.common.dto.PaymentViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.PayStatus;
import com.claw.server.common.enums.WalletTxnStatus;
import com.claw.server.common.event.OutboxPublisher;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 支付域单元测试：充值收单/回调入账（幂等）/三专户收单/提现状态机/出金失败退回/
 * 扫码购（R1）清分触发事件发布。
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentOrderRepository paymentOrderRepository;
    @Mock private WalletTxnRepository walletTxnRepository;
    @Mock private AccountService accountService;
    @Mock private LedgerService ledgerService;
    @Mock private AbaGateway abaGateway;
    @Mock private OutboxPublisher outboxPublisher;
    @InjectMocks private PaymentService service;

    private Account account(Long id, String balance) {
        return Account.builder().id(id).balance(new BigDecimal(balance)).build();
    }

    private PaymentOrder createdOrder(String no, String escrowType) {
        return PaymentOrder.builder().orderNo(no).bizRef("X").amountUsd(new BigDecimal("10.00"))
                .channel("khqr").paymentMethod("KHQR").status(PayStatus.CREATED)
                .escrowType(escrowType).build();
    }

    private WalletTxn createdTxn(String no, String type) {
        return WalletTxn.builder().txnNo(no).userId(100L).txnType(type)
                .amountUsd(new BigDecimal("10.00")).paymentOrderNo("PAY-1")
                .status(WalletTxnStatus.CREATED).build();
    }

    // ------------------------------------------------------------------
    // 充值收单
    // ------------------------------------------------------------------
    @Test
    void recharge_creates_order_and_returns_khqr() {
        when(walletTxnRepository.save(any(WalletTxn.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(abaGateway.createKhqr(anyString(), any(BigDecimal.class))).thenReturn("KHQR://claw/test");

        PaymentViews.RechargeView v = service.recharge(100L, new PaymentRequests.Recharge(new BigDecimal("10.00"), null));

        assertNotNull(v.orderNo());
        assertEquals("KHQR://claw/test", v.khqr());
        assertEquals("KHQR", v.paymentMethod());
        verify(abaGateway).createKhqr(anyString(), eq(new BigDecimal("10.00")));
    }

    // ------------------------------------------------------------------
    // 回调入账
    // ------------------------------------------------------------------
    @Test
    void callback_posts_recharge_entry_and_marks_paid() {
        when(paymentOrderRepository.findByOrderNo("PAY-1")).thenReturn(Optional.of(createdOrder("PAY-1", null)));
        when(walletTxnRepository.findByPaymentOrderNo("PAY-1")).thenReturn(Optional.of(createdTxn("RCH-1", "RECHARGE")));
        when(accountService.getOrCreatePlatformAccount(AccountType.MASTER)).thenReturn(account(1L, "100.00"));
        when(accountService.getOrCreateUserAccount(100L)).thenReturn(account(2L, "0.00"));

        PaymentViews.WalletTxnView v = service.onCallback("PAY-1");

        assertEquals("PAID", v.status());
        verify(ledgerService).postEntries(eq(BizType.RECHARGE), eq("PAY-1"), anyList());
    }

    @Test
    void callback_posts_escrow_collect_to_target_account() {
        when(paymentOrderRepository.findByOrderNo("COL-1"))
                .thenReturn(Optional.of(createdOrder("COL-1", "RESIDUAL_RESERVE")));
        when(walletTxnRepository.findByPaymentOrderNo("COL-1"))
                .thenReturn(Optional.of(createdTxn("COL-1", "ESCROW_COLLECT")));
        when(accountService.getOrCreatePlatformAccount(AccountType.MASTER)).thenReturn(account(1L, "100.00"));
        when(accountService.getOrCreatePlatformAccount(AccountType.RESIDUAL_RESERVE)).thenReturn(account(3L, "0.00"));

        PaymentViews.WalletTxnView v = service.onCallback("COL-1");

        assertEquals("PAID", v.status());
        verify(ledgerService).postEntries(eq(BizType.ESCROW_COLLECT), eq("COL-1"), anyList());
    }

    @Test
    void callback_rejects_duplicate() {
        PaymentOrder paid = createdOrder("PAY-1", null);
        paid.setStatus(PayStatus.PAID);
        when(paymentOrderRepository.findByOrderNo("PAY-1")).thenReturn(Optional.of(paid));

        assertThrows(Exception.class, () -> service.onCallback("PAY-1"));
        verify(ledgerService, never()).postEntries(any(), anyString(), anyList());
    }

    // ------------------------------------------------------------------
    // 三专户收单创建
    // ------------------------------------------------------------------
    @Test
    void collect_creates_escrow_order() {
        when(walletTxnRepository.save(any(WalletTxn.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(abaGateway.createKhqr(anyString(), any(BigDecimal.class))).thenReturn("KHQR://claw/col");

        PaymentViews.CollectView v = service.collect(100L,
                new PaymentRequests.Collect(new BigDecimal("50.00"), AccountType.BATTERY_FUND));

        assertEquals("BATTERY_FUND", v.escrowType());
        assertEquals("KHQR://claw/col", v.khqr());
    }

    @Test
    void collect_rejects_invalid_escrow_type() {
        assertThrows(Exception.class, () -> service.collect(100L,
                new PaymentRequests.Collect(new BigDecimal("50.00"), AccountType.MASTER)));
        verify(walletTxnRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // 提现状态机
    // ------------------------------------------------------------------
    @Test
    void withdraw_debits_ledger_and_marks_success() {
        when(accountService.getOrCreateUserAccount(100L)).thenReturn(account(2L, "100.00"));
        when(accountService.getOrCreatePlatformAccount(AccountType.MASTER)).thenReturn(account(1L, "0.00"));
        when(walletTxnRepository.save(any(WalletTxn.class))).thenAnswer(inv -> inv.getArgument(0));
        when(abaGateway.payout(anyString(), any(BigDecimal.class), anyString())).thenReturn("ABA-PAYOUT-X");

        PaymentViews.WalletTxnView v = service.withdraw(100L,
                new PaymentRequests.Withdraw(new BigDecimal("10.00"), "000123", "ABA_BANK"));

        assertEquals("SUCCESS", v.status());
        assertEquals("ABA-PAYOUT-X", v.abaRef());
        verify(ledgerService).postEntries(eq(BizType.WITHDRAW), anyString(), anyList());
    }

    @Test
    void withdraw_rejects_when_balance_insufficient() {
        when(accountService.getOrCreateUserAccount(100L)).thenReturn(account(2L, "5.00"));

        assertThrows(Exception.class, () -> service.withdraw(100L,
                new PaymentRequests.Withdraw(new BigDecimal("10.00"), "000123", null)));
        verify(ledgerService, never()).postEntries(any(), anyString(), anyList());
    }

    @Test
    void fail_withdraw_refunds_to_ledger() {
        WalletTxn processing = WalletTxn.builder().txnNo("WDR-1").userId(100L).txnType("WITHDRAW")
                .amountUsd(new BigDecimal("10.00")).status(WalletTxnStatus.PROCESSING).build();
        when(walletTxnRepository.findByTxnNo("WDR-1")).thenReturn(Optional.of(processing));
        when(accountService.getOrCreateUserAccount(100L)).thenReturn(account(2L, "90.00"));
        when(accountService.getOrCreatePlatformAccount(AccountType.MASTER)).thenReturn(account(1L, "10.00"));

        PaymentViews.WalletTxnView v = service.failWithdraw("WDR-1", "ABA timeout");

        assertEquals("FAILED_REFUND", v.status());
        verify(ledgerService).postEntries(eq(BizType.WITHDRAW), eq("WDR-1:REFUND"), anyList());
    }

    // ------------------------------------------------------------------
    // R1 扫码购：支付成功后发布清分触发事件（outbox，仅扫码购单）
    // ------------------------------------------------------------------
    @Test
    void callback_scanPurchase_publishesClearingTriggerEvent() {
        PaymentOrder scan = PaymentOrder.builder().id(9L).orderNo("PAY-S1")
                .bizRef(PaymentService.SCAN_PURCHASE_BIZ_REF_PREFIX + "T-1")
                .amountUsd(new BigDecimal("100.00")).channel("khqr").paymentMethod("SCAN_PURCHASE")
                .status(PayStatus.CREATED).build();
        when(paymentOrderRepository.findByOrderNo("PAY-S1")).thenReturn(Optional.of(scan));
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(walletTxnRepository.findByPaymentOrderNo("PAY-S1"))
                .thenReturn(Optional.of(createdTxn("RCH-S1", "SCAN_PURCHASE")));
        when(accountService.getOrCreatePlatformAccount(AccountType.MASTER)).thenReturn(account(1L, "0.00"));
        when(accountService.getOrCreateUserAccount(100L)).thenReturn(account(2L, "0.00"));

        service.onCallback("PAY-S1");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxPublisher).publish(eq("payment_order"), eq(9L),
                eq(PaymentService.EVENT_PAYMENT_PAID), payload.capture());
        assertTrue(payload.getValue().contains("PAY-S1"), "载荷须带订单号供 handler 定位（金额一律以 DB 为权威）");
    }

    @Test
    void callback_recharge_doesNotPublishClearingTriggerEvent() {
        when(paymentOrderRepository.findByOrderNo("PAY-1")).thenReturn(Optional.of(createdOrder("PAY-1", null)));
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(walletTxnRepository.findByPaymentOrderNo("PAY-1")).thenReturn(Optional.of(createdTxn("RCH-1", "RECHARGE")));
        when(accountService.getOrCreatePlatformAccount(AccountType.MASTER)).thenReturn(account(1L, "100.00"));
        when(accountService.getOrCreateUserAccount(100L)).thenReturn(account(2L, "0.00"));

        service.onCallback("PAY-1");

        verify(outboxPublisher, never()).publish(anyString(), any(), anyString(), anyString());
    }
}
