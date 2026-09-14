package com.claw.server.domain.clearing;

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
 * R5 共享池分成清分测试（T08 + T11 集成）。
 *
 * <p>模拟 {@code SharedPoolService.completeRental} 触发的清分：租赁 $100，四方入账
 * （OWNER 残差 70 / STATION 15 / PLATFORM 10 / INSURER 5），其中 OWNER 腿经 WHT 代扣
 * （INDIVIDUAL + RENTAL → 10%，gross 70 → wht 7 → net 63）。
 *
 * <p>验证点：四腿各自借贷守恒；WHT 腿拆成「借对冲 70 / 贷应付所有人 63 / 贷 WHT_PAYABLE 7」；
 * 代扣台账落库一条（gross 70 / wht 7 / net 63）；清分指令金额取净额（OWNER=63，其余=gross）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class R5ClearingTest {

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

    private static final String BIZ_SCENE = "RENTAL_SPLIT";
    private static final String BASIS_REF = "RNT-AAAA1234";
    private static final Long OFFSET_ACCOUNT_ID = 1L;

    @BeforeEach
    void setUp() {
        when(ledgerService.getPlatformAccountId()).thenReturn(OFFSET_ACCOUNT_ID);
        when(accountService.getOrCreatePlatformAccount(AccountType.WHT_PAYABLE))
                .thenReturn(Account.builder().id(99L).accountType(AccountType.WHT_PAYABLE).build());
        when(accountService.getOrCreatePlatformAccount(AccountType.PAYABLE_OWNER))
                .thenReturn(Account.builder().id(14L).accountType(AccountType.PAYABLE_OWNER).build());
        when(accountService.getOrCreatePlatformAccount(AccountType.PAYABLE_STATION))
                .thenReturn(Account.builder().id(12L).accountType(AccountType.PAYABLE_STATION).build());
        when(accountService.getOrCreatePlatformAccount(AccountType.PLATFORM_REVENUE))
                .thenReturn(Account.builder().id(11L).accountType(AccountType.PLATFORM_REVENUE).build());
        when(accountService.getOrCreatePlatformAccount(AccountType.PAYABLE_INSURER))
                .thenReturn(Account.builder().id(15L).accountType(AccountType.PAYABLE_INSURER).build());

        // OWNER 腿 gross=70 → WHT 10% → wht 7 / net 63；其余腿不代扣
        when(whtEngine.compute(nullable(Long.class), any(BigDecimal.class), any(String.class))).thenAnswer(inv -> {
            BigDecimal gross = inv.getArgument(1);
            if (gross.compareTo(new BigDecimal("70.0000")) == 0) {
                return new WhtEngine.WhtResult(gross, new BigDecimal("0.10"),
                        new BigDecimal("7.0000"), new BigDecimal("63.0000"));
            }
            return WhtEngine.WhtResult.noWithholding(gross);
        });

        when(clearingInstructionService.create(any(), any(), any(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    SplitEngine.SplitLeg leg = inv.getArgument(2);
                    String basisRef = inv.getArgument(3);
                    return ClearingInstruction.builder().id(100L).amount(inv.getArgument(7))
                            .ledgerBizRef(basisRef + ":" + leg.payeeType())
                            .scene(ClearingScene.R5).mode(ClearingMode.AT_SOURCE)
                            .status(ClearingStatus.CREATED).build();
                });
    }

    private static SplitEngine.SplitLeg leg(String payee, String amount, int priority) {
        return new SplitEngine.SplitLeg(payee, new BigDecimal(amount), "T+7", priority, 9L);
    }

    private static ClearingRequests.Settle settle(String total) {
        return new ClearingRequests.Settle(ClearingScene.R5, BIZ_SCENE, BASIS_REF, new BigDecimal(total),
                null, "USD", ClearingMode.AT_SOURCE, "ABA_PAYWAY");
    }

    @Test
    void r5_fourWaySplit_withWhtOnOwner_isBalanced() {
        List<SplitEngine.SplitLeg> legs = List.of(
                leg("PLATFORM", "10.0000", 1),
                leg("STATION", "15.0000", 10),
                leg("INSURER", "5.0000", 20),
                leg("OWNER", "70.0000", 99)); // 残差兜底
        when(clearingInstructionService.findByBasisRef(BASIS_REF)).thenReturn(List.of());
        when(splitEngine.compute(eq(BIZ_SCENE), any(), any(), eq("USD"))).thenReturn(legs);

        ClearingService.ClearingResult result = clearingService.settle(settle("100"));

        assertEquals(4, result.legCount());
        assertFalse(result.idempotentReplay());

        // 捕获每腿的分录与 bizRef
        ArgumentCaptor<String> refCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<LedgerRequests.Entry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerService, times(4)).postEntries(eq(BizType.CLEARING_SETTLE), refCaptor.capture(), entriesCaptor.capture());
        List<String> refs = refCaptor.getAllValues();
        List<List<LedgerRequests.Entry>> batches = entriesCaptor.getAllValues();

        // OWNER 腿：3 分录（借 70 / 贷 63 / 贷 7），借贷守恒
        int ownerIdx = refs.indexOf(BASIS_REF + ":OWNER");
        assertTrue(ownerIdx >= 0, "应存在 OWNER 腿分录");
        List<LedgerRequests.Entry> ownerEntries = batches.get(ownerIdx);
        assertEquals(3, ownerEntries.size());
        BigDecimal ownerDebit = ownerEntries.stream().filter(e -> e.direction() == LedgerRequests.Direction.D)
                .map(LedgerRequests.Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ownerCredit = ownerEntries.stream().filter(e -> e.direction() == LedgerRequests.Direction.C)
                .map(LedgerRequests.Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, ownerDebit.compareTo(ownerCredit));
        assertEquals(0, ownerDebit.compareTo(new BigDecimal("70.0000")));

        // 其余腿：2 分录（借 gross / 贷 gross）
        for (int i = 0; i < refs.size(); i++) {
            if (!refs.get(i).endsWith(":OWNER")) {
                assertEquals(2, batches.get(i).size());
            }
        }

        // 代扣台账落库一条：gross 70 / wht 7 / net 63
        ArgumentCaptor<TaxWithholding> twCaptor = ArgumentCaptor.forClass(TaxWithholding.class);
        verify(taxWithholdingRepository).save(twCaptor.capture());
        TaxWithholding tw = twCaptor.getValue();
        assertEquals(0, tw.getGrossAmount().compareTo(new BigDecimal("70.0000")));
        assertEquals(0, tw.getWhtAmount().compareTo(new BigDecimal("7.0000")));
        assertEquals(0, tw.getNetAmount().compareTo(new BigDecimal("63.0000")));
        assertEquals("UNPAID", tw.getPaidStatus());
        assertNotNull(tw.getTaxPeriod());

        // 指令金额：OWNER=净额 63，其余=毛额（10/15/5）
        for (ClearingInstruction ci : result.instructions()) {
            String ref = ci.getLedgerBizRef();
            if (ref.endsWith(":OWNER")) {
                assertEquals(0, ci.getAmount().compareTo(new BigDecimal("63.0000")));
            } else if (ref.endsWith(":STATION")) {
                assertEquals(0, ci.getAmount().compareTo(new BigDecimal("15.0000")));
            } else if (ref.endsWith(":PLATFORM")) {
                assertEquals(0, ci.getAmount().compareTo(new BigDecimal("10.0000")));
            } else if (ref.endsWith(":INSURER")) {
                assertEquals(0, ci.getAmount().compareTo(new BigDecimal("5.0000")));
            }
        }
    }
}
