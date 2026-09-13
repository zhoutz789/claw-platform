package com.claw.server.domain.clearing;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ClearingRequests;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.ClearingMode;
import com.claw.server.common.enums.ClearingScene;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ClearingService} 单元测试（Mockito，无 Docker/PG）。
 * 覆盖：逐腿 postEntries 次数与 bizRef 后缀、借贷守恒、平台对冲借方、
 * 收款账户解析、同 basisRef 幂等重放不重复入账、非法入参。
 */
@ExtendWith(MockitoExtension.class)
class ClearingServiceTest {

    @Mock
    private SplitEngine splitEngine;

    @Mock
    private ClearingInstructionService clearingInstructionService;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private AccountService accountService;

    @InjectMocks
    private ClearingService clearingService;

    private static final String SCENE = "CONSIGNMENT_SCAN";
    private static final String BASIS_REF = "ORD-1";
    private static final Long OFFSET_ACCOUNT_ID = 1L;

    private static SplitEngine.SplitLeg leg(String payee, String amount) {
        return new SplitEngine.SplitLeg(payee, new BigDecimal(amount), "T+0", 1, 9L);
    }

    private static ClearingRequests.Settle settle(String total) {
        return new ClearingRequests.Settle(ClearingScene.R1, SCENE, BASIS_REF, new BigDecimal(total),
                null, "USD", ClearingMode.AT_SOURCE, "ABA_PAYWAY");
    }

    private void stubPayeeAccounts() {
        when(accountService.getOrCreatePlatformAccount(AccountType.PLATFORM_REVENUE))
                .thenReturn(Account.builder().id(11L).accountType(AccountType.PLATFORM_REVENUE).build());
        when(accountService.getOrCreatePlatformAccount(AccountType.PAYABLE_STATION))
                .thenReturn(Account.builder().id(12L).accountType(AccountType.PAYABLE_STATION).build());
        when(accountService.getOrCreatePlatformAccount(AccountType.PAYABLE_LOGISTICS))
                .thenReturn(Account.builder().id(13L).accountType(AccountType.PAYABLE_LOGISTICS).build());
        when(accountService.getOrCreatePlatformAccount(AccountType.PAYABLE_MFG))
                .thenReturn(Account.builder().id(14L).accountType(AccountType.PAYABLE_MFG).build());
    }

    @SuppressWarnings("unchecked")
    private List<List<LedgerRequests.Entry>> captureEntryBatches() {
        ArgumentCaptor<List<LedgerRequests.Entry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerService, times(4)).postEntries(eq(BizType.CLEARING_SETTLE), anyString(), captor.capture());
        return captor.getAllValues();
    }

    @Test
    void settle_postsOneLegPerSplit_withBalancedEntries() {
        List<SplitEngine.SplitLeg> legs = List.of(
                leg("PLATFORM", "5.0000"), leg("STATION", "10.0000"),
                leg("LOGISTICS", "5.0000"), leg("MANUFACTURER", "80.0000"));
        when(clearingInstructionService.findByBasisRef(BASIS_REF)).thenReturn(List.of());
        when(splitEngine.compute(eq(SCENE), any(), any(), eq("USD"))).thenReturn(legs);
        when(ledgerService.getPlatformAccountId()).thenReturn(OFFSET_ACCOUNT_ID);
        stubPayeeAccounts();
        when(clearingInstructionService.create(any(), any(), any(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> ClearingInstruction.builder().id(100L)
                        .scene(ClearingScene.R1).mode(ClearingMode.AT_SOURCE)
                        .status(com.claw.server.common.enums.ClearingStatus.CREATED).build());

        ClearingService.ClearingResult result = clearingService.settle(settle("100"));

        assertEquals(4, result.legCount());
        assertFalse(result.idempotentReplay());

        List<List<LedgerRequests.Entry>> batches = captureEntryBatches();
        assertEquals(4, batches.size());
        for (List<LedgerRequests.Entry> entries : batches) {
            assertEquals(2, entries.size());
            LedgerRequests.Entry debit = entries.get(0);
            LedgerRequests.Entry credit = entries.get(1);
            assertEquals(LedgerRequests.Direction.D, debit.direction());
            assertEquals(LedgerRequests.Direction.C, credit.direction());
            assertEquals(OFFSET_ACCOUNT_ID, debit.accountId());           // 借：平台对冲户
            assertEquals(0, debit.amount().compareTo(credit.amount()));   // 借贷守恒
        }
    }

    @Test
    void settle_usesPayeeTypeSuffixedBizRef() {
        List<SplitEngine.SplitLeg> legs = List.of(leg("PLATFORM", "100.0000"));
        when(clearingInstructionService.findByBasisRef(BASIS_REF)).thenReturn(List.of());
        when(splitEngine.compute(eq(SCENE), any(), any(), eq("USD"))).thenReturn(legs);
        when(ledgerService.getPlatformAccountId()).thenReturn(OFFSET_ACCOUNT_ID);
        when(accountService.getOrCreatePlatformAccount(AccountType.PLATFORM_REVENUE))
                .thenReturn(Account.builder().id(11L).build());
        when(clearingInstructionService.create(any(), any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(ClearingInstruction.builder().id(1L).build());

        clearingService.settle(settle("100"));

        verify(ledgerService).postEntries(eq(BizType.CLEARING_SETTLE), eq("ORD-1:PLATFORM"), anyList());
    }

    @Test
    void settle_sameBasisRefTwice_isIdempotentNoPosting() {
        ClearingInstruction existing = ClearingInstruction.builder()
                .id(5L).instructionNo("CI-R1-1-AAAAAA").basisRef(BASIS_REF)
                .amount(new BigDecimal("5.0000")).build();
        when(clearingInstructionService.findByBasisRef(BASIS_REF)).thenReturn(List.of(existing));

        ClearingService.ClearingResult result = clearingService.settle(settle("100"));

        assertTrue(result.idempotentReplay());
        assertEquals(1, result.legCount());
        verify(ledgerService, never()).postEntries(any(), anyString(), anyList());
        verify(splitEngine, never()).compute(anyString(), any(), any(), anyString());
    }

    @Test
    void settle_unsupportedPayee_throwsAndPostsNothing() {
        when(clearingInstructionService.findByBasisRef(BASIS_REF)).thenReturn(List.of());
        when(splitEngine.compute(eq(SCENE), any(), any(), eq("USD")))
                .thenReturn(List.of(leg("INSURANCE", "100.0000")));
        when(ledgerService.getPlatformAccountId()).thenReturn(OFFSET_ACCOUNT_ID);

        BizException ex = assertThrows(BizException.class, () -> clearingService.settle(settle("100")));

        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        verify(ledgerService, never()).postEntries(any(), anyString(), anyList());
    }

    @Test
    void settle_invalidCommand_throwsInvalidParam() {
        ClearingRequests.Settle zero = new ClearingRequests.Settle(ClearingScene.R1, SCENE, BASIS_REF,
                BigDecimal.ZERO, null, "USD", ClearingMode.AT_SOURCE, null);
        assertThrows(BizException.class, () -> clearingService.settle(zero));

        ClearingRequests.Settle blankRef = new ClearingRequests.Settle(ClearingScene.R1, SCENE, "  ",
                new BigDecimal("100"), null, "USD", ClearingMode.AT_SOURCE, null);
        assertThrows(BizException.class, () -> clearingService.settle(blankRef));
    }

    @Test
    void findByIdemKey_delegates() {
        ClearingInstruction ci = ClearingInstruction.builder().id(3L).build();
        when(clearingInstructionService.findByIdemKey("R1:ORD-1:PLATFORM")).thenReturn(java.util.Optional.of(ci));

        assertEquals(3L, clearingService.findByIdemKey("R1:ORD-1:PLATFORM").orElseThrow().getId());
    }
}
