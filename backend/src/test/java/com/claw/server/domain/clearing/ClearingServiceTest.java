package com.claw.server.domain.clearing;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ClearingRequests;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.ClearingMode;
import com.claw.server.common.enums.ClearingScene;
import com.claw.server.common.enums.ClearingStatus;
import com.claw.server.domain.funds.VirtualSubAccountRepository;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ClearingService} 单元测试（Mockito，无 Docker/PG）。
 * 覆盖：逐腿 postEntries 次数与 bizRef 后缀、借贷守恒、平台对冲借方、收款账户解析、
 * 同 basisRef 幂等重放不重复入账、非法入参、WHT 代扣腿（3 分录 + 代扣台账 + 指令净额）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClearingServiceTest {

    @Mock
    private SplitEngine splitEngine;

    @Mock
    private ClearingInstructionService clearingInstructionService;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private AccountService accountService;

    @Mock
    private WhtEngine whtEngine;

    @Mock
    private VirtualSubAccountRepository virtualSubAccountRepository;

    @Mock
    private TaxWithholdingRepository taxWithholdingRepository;

    @InjectMocks
    private ClearingService clearingService;

    private static final String SCENE = "CONSIGNMENT_SCAN";
    private static final String BASIS_REF = "ORD-1";
    private static final Long OFFSET_ACCOUNT_ID = 1L;

    @BeforeEach
    void setUp() {
        when(ledgerService.getPlatformAccountId()).thenReturn(OFFSET_ACCOUNT_ID);
        when(accountService.getOrCreatePlatformAccount(AccountType.WHT_PAYABLE))
                .thenReturn(Account.builder().id(99L).accountType(AccountType.WHT_PAYABLE).build());
        // 默认：所有腿不代扣 WHT（net=gross），保持既有用例行为
        when(whtEngine.compute(nullable(Long.class), any(BigDecimal.class), any(String.class)))
                .thenAnswer(inv -> WhtEngine.WhtResult.noWithholding(inv.getArgument(1)));
        // 指令创建：回显净额（第 8 参），默认 gross=gross
        when(clearingInstructionService.create(any(), any(), any(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> ClearingInstruction.builder().id(100L)
                        .amount(inv.getArgument(7))
                        .scene(ClearingScene.R1).mode(ClearingMode.AT_SOURCE)
                        .status(ClearingStatus.CREATED).build());
    }

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
    private List<List<LedgerRequests.Entry>> captureEntryBatches(int times) {
        ArgumentCaptor<List<LedgerRequests.Entry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerService, times(times)).postEntries(eq(BizType.CLEARING_SETTLE), anyString(), captor.capture());
        return captor.getAllValues();
    }

    @Test
    void settle_postsOneLegPerSplit_withBalancedEntries() {
        List<SplitEngine.SplitLeg> legs = List.of(
                leg("PLATFORM", "5.0000"), leg("STATION", "10.0000"),
                leg("LOGISTICS", "5.0000"), leg("MANUFACTURER", "80.0000"));
        when(clearingInstructionService.findByBasisRef(BASIS_REF)).thenReturn(List.of());
        when(splitEngine.compute(eq(SCENE), any(), any(), eq("USD"))).thenReturn(legs);
        stubPayeeAccounts();

        ClearingService.ClearingResult result = clearingService.settle(settle("100"));

        assertEquals(4, result.legCount());
        assertFalse(result.idempotentReplay());

        List<List<LedgerRequests.Entry>> batches = captureEntryBatches(4);
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
        when(accountService.getOrCreatePlatformAccount(AccountType.PLATFORM_REVENUE))
                .thenReturn(Account.builder().id(11L).build());

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

    // ===================== WHT 代扣（T11）=====================

    @Test
    void settle_withWhtOnOneLeg_postsThreeEntriesAndSavesWithholding() {
        List<SplitEngine.SplitLeg> legs = List.of(leg("MANUFACTURER", "100.0000"));
        when(clearingInstructionService.findByBasisRef(BASIS_REF)).thenReturn(List.of());
        when(splitEngine.compute(eq(SCENE), any(), any(), eq("USD"))).thenReturn(legs);
        when(accountService.getOrCreatePlatformAccount(AccountType.PAYABLE_MFG))
                .thenReturn(Account.builder().id(14L).build());
        // MANUFACTURER 腿：INDIVIDUAL + RENTAL → WHT 10%（gross 100 → wht 10, net 90）
        when(whtEngine.compute(nullable(Long.class), any(BigDecimal.class), any(String.class)))
                .thenReturn(new WhtEngine.WhtResult(new BigDecimal("100.0000"), new BigDecimal("0.10"),
                        new BigDecimal("10.0000"), new BigDecimal("90.0000")));
        when(clearingInstructionService.create(any(), any(), any(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> ClearingInstruction.builder().id(200L).amount(inv.getArgument(7))
                        .scene(ClearingScene.R1).mode(ClearingMode.AT_SOURCE)
                        .status(ClearingStatus.CREATED).build());

        ClearingService.ClearingResult result = clearingService.settle(settle("100"));

        assertEquals(1, result.legCount());
        // 借贷守恒：借 100（对冲户）/ 贷 90（应付厂家）/ 贷 10（WHT_PAYABLE）
        ArgumentCaptor<List<LedgerRequests.Entry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postEntries(eq(BizType.CLEARING_SETTLE), eq("ORD-1:MANUFACTURER"), captor.capture());
        List<LedgerRequests.Entry> entries = captor.getValue();
        assertEquals(3, entries.size());
        BigDecimal debit = entries.stream().filter(e -> e.direction() == LedgerRequests.Direction.D)
                .map(LedgerRequests.Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = entries.stream().filter(e -> e.direction() == LedgerRequests.Direction.C)
                .map(LedgerRequests.Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, debit.compareTo(credit));
        assertEquals(0, debit.compareTo(new BigDecimal("100.0000")));

        // 代扣台账落库一条
        ArgumentCaptor<TaxWithholding> twCaptor = ArgumentCaptor.forClass(TaxWithholding.class);
        verify(taxWithholdingRepository).save(twCaptor.capture());
        TaxWithholding tw = twCaptor.getValue();
        assertEquals(0, tw.getGrossAmount().compareTo(new BigDecimal("100.0000")));
        assertEquals(0, tw.getWhtAmount().compareTo(new BigDecimal("10.0000")));
        assertEquals(0, tw.getNetAmount().compareTo(new BigDecimal("90.0000")));
        assertEquals("UNPAID", tw.getPaidStatus());
        assertNotNull(tw.getTaxPeriod());
        assertEquals(7, tw.getTaxPeriod().length());

        // 指令金额取净额 net
        assertEquals(0, result.instructions().get(0).getAmount().compareTo(new BigDecimal("90.0000")));
    }

    @Test
    void settle_zeroWht_doesNotWriteWithholdingAndPostsTwoEntries() {
        List<SplitEngine.SplitLeg> legs = List.of(leg("PLATFORM", "100.0000"));
        when(clearingInstructionService.findByBasisRef(BASIS_REF)).thenReturn(List.of());
        when(splitEngine.compute(eq(SCENE), any(), any(), eq("USD"))).thenReturn(legs);
        when(accountService.getOrCreatePlatformAccount(AccountType.PLATFORM_REVENUE))
                .thenReturn(Account.builder().id(11L).build());

        clearingService.settle(settle("100"));

        verify(ledgerService).postEntries(eq(BizType.CLEARING_SETTLE), eq("ORD-1:PLATFORM"), anyList());
        verify(taxWithholdingRepository, never()).save(any());
        verify(whtEngine).compute(isNull(), eq(new BigDecimal("100.0000")), eq("USD"));
    }
}
