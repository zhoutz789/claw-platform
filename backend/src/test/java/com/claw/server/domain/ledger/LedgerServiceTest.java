package com.claw.server.domain.ledger;

import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.enums.BizType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 复式记账引擎单元测试（Mockito，无需 DB）。
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AccountEntryRepository entryRepository;
    @InjectMocks
    private LedgerService service;

    private Account account(Long id, BigDecimal balance) {
        return Account.builder().id(id).balance(balance).build();
    }

    @Test
    void postEntries_balances_credit_into_account() {
        Account master = account(1L, new BigDecimal("100.00"));
        Account locked = account(2L, BigDecimal.ZERO);
        when(entryRepository.existsByBizTypeAndBizRef(any(), any())).thenReturn(false);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(master));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(locked));

        var result = service.postEntries(BizType.DEPOSIT_HOLD, "DEP-TEST001", List.of(
                new LedgerRequests.Entry(1L, LedgerRequests.Direction.D, new BigDecimal("30.00"), "押金"),
                new LedgerRequests.Entry(2L, LedgerRequests.Direction.C, new BigDecimal("30.00"), "冻结")));

        assertNotNull(result.txnId());
        assertEquals(2, result.entryCount());
        assertEquals(new BigDecimal("70.00"), master.getBalance());   // 出账 100-30
        assertEquals(new BigDecimal("30.00"), locked.getBalance());   // 入账 0+30
        verify(entryRepository, times(2)).save(any(AccountEntry.class));
    }

    @Test
    void postEntries_rejects_unbalanced_entries() {
        when(entryRepository.existsByBizTypeAndBizRef(any(), any())).thenReturn(false);

        assertThrows(Exception.class, () -> service.postEntries(BizType.SWAP_PAY, "SWAP-1", List.of(
                new LedgerRequests.Entry(1L, LedgerRequests.Direction.D, new BigDecimal("10.00"), null),
                new LedgerRequests.Entry(2L, LedgerRequests.Direction.C, new BigDecimal("9.00"), null))));

        verify(accountRepository, never()).findById(any());
        verify(entryRepository, never()).save(any());
    }

    @Test
    void postEntries_is_idempotent_for_same_bizRef() {
        when(entryRepository.existsByBizTypeAndBizRef(BizType.RECHARGE.name(), "R-001")).thenReturn(true);

        assertThrows(Exception.class, () -> service.postEntries(BizType.RECHARGE, "R-001", List.of(
                new LedgerRequests.Entry(1L, LedgerRequests.Direction.D, new BigDecimal("5.00"), null),
                new LedgerRequests.Entry(2L, LedgerRequests.Direction.C, new BigDecimal("5.00"), null))));

        verify(accountRepository, never()).findById(any());
    }

    @Test
    void postEntries_rejects_when_balance_insufficient() {
        Account master = account(1L, new BigDecimal("10.00"));
        Account locked = account(2L, BigDecimal.ZERO);
        when(entryRepository.existsByBizTypeAndBizRef(any(), any())).thenReturn(false);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(master));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(locked));

        assertThrows(Exception.class, () -> service.postEntries(BizType.DEPOSIT_HOLD, "DEP-X", List.of(
                new LedgerRequests.Entry(1L, LedgerRequests.Direction.D, new BigDecimal("50.00"), null),
                new LedgerRequests.Entry(2L, LedgerRequests.Direction.C, new BigDecimal("50.00"), null))));

        assertEquals(new BigDecimal("10.00"), master.getBalance());   // 未变动
        verify(entryRepository, never()).save(any());
    }

    @Test
    void postEntries_rejects_empty_entries() {
        assertThrows(Exception.class,
                () -> service.postEntries(BizType.REFUND, "R-2", List.of()));
    }
}
