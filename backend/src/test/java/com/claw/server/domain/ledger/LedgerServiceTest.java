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

    /**
     * V85：豁免条件收窄为「MASTER 且 userId == NULL」后必须钉死这两条，
     * 否则 415 个测试全绿也证明不了修复生效 —— 原 helper 造出的账户 accountType 为 null，
     * 本来就从不被豁免，改前改后都会通过。
     */
    @Test
    void postEntries_userMasterAccountCannotOverdraw() {
        // 用户主账户：accountType=MASTER 但 userId 非 NULL → 必须校验余额
        Account user = Account.builder().id(1L).balance(new BigDecimal("10.00"))
                .accountType(com.claw.server.common.enums.AccountType.MASTER).userId(15L).build();
        when(entryRepository.existsByBizTypeAndBizRef(any(), any())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));

        // 余额 10.00 < 扣 30.00 → 必须被拒（改动前这里会静默扣成 -20）
        assertThrows(com.claw.server.common.api.BizException.class, () ->
                service.postEntries(BizType.DEPOSIT_HOLD, "DEP-OVERDRAW", List.of(
                        new LedgerRequests.Entry(1L, LedgerRequests.Direction.D, new BigDecimal("30.00"), "押金"),
                        new LedgerRequests.Entry(2L, LedgerRequests.Direction.C, new BigDecimal("30.00"), "冻结"))));
        assertEquals(new BigDecimal("10.00"), user.getBalance()); // 一分未动
    }

    @Test
    void postEntries_platformInternalAccountStillExempt() {
        // 平台内部户：MASTER 且 userId 为 NULL → 仍豁免，允许余额为负（PROJECT_LEDGER 依赖这点）
        Account platform = Account.builder().id(1L).balance(BigDecimal.ZERO)
                .accountType(com.claw.server.common.enums.AccountType.MASTER).userId(null).build();
        Account project = account(2L, BigDecimal.ZERO);
        when(entryRepository.existsByBizTypeAndBizRef(any(), any())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(platform));
        when(accountRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(project));

        service.postEntries(BizType.PROJECT_LEDGER, "PRJ-1", List.of(
                new LedgerRequests.Entry(1L, LedgerRequests.Direction.D, new BigDecimal("100.00"), "平台汇总户"),
                new LedgerRequests.Entry(2L, LedgerRequests.Direction.C, new BigDecimal("100.00"), "项目户")));

        assertEquals(new BigDecimal("-100.00"), platform.getBalance()); // 豁免生效，允许为负
        assertEquals(new BigDecimal("100.00"), project.getBalance());
    }

    @Test
    void postEntries_balances_credit_into_account() {
        Account master = account(1L, new BigDecimal("100.00"));
        Account locked = account(2L, BigDecimal.ZERO);
        when(entryRepository.existsByBizTypeAndBizRef(any(), any())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(master));
        when(accountRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(locked));

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

        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).save(any());
    }

    @Test
    void postEntries_is_idempotent_for_same_bizRef() {
        when(entryRepository.existsByBizTypeAndBizRef(BizType.RECHARGE.name(), "R-001")).thenReturn(true);

        assertThrows(Exception.class, () -> service.postEntries(BizType.RECHARGE, "R-001", List.of(
                new LedgerRequests.Entry(1L, LedgerRequests.Direction.D, new BigDecimal("5.00"), null),
                new LedgerRequests.Entry(2L, LedgerRequests.Direction.C, new BigDecimal("5.00"), null))));

        verify(accountRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void postEntries_rejects_when_balance_insufficient() {
        Account master = account(1L, new BigDecimal("10.00"));
        when(entryRepository.existsByBizTypeAndBizRef(any(), any())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(master));

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
