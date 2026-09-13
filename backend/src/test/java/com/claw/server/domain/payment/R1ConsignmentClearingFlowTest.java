package com.claw.server.domain.payment;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.ClearingMode;
import com.claw.server.common.enums.ClearingStatus;
import com.claw.server.common.enums.PayStatus;
import com.claw.server.common.enums.RuleBasis;
import com.claw.server.domain.clearing.ClearingInstruction;
import com.claw.server.domain.clearing.ClearingInstructionRepository;
import com.claw.server.domain.clearing.ClearingInstructionService;
import com.claw.server.domain.clearing.ClearingService;
import com.claw.server.domain.clearing.SettlementRule;
import com.claw.server.domain.clearing.SettlementRuleRepository;
import com.claw.server.domain.clearing.SplitEngine;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R1 扫码购分账<b>端到端链路</b>测试（Mockito，无 Docker/PG）。
 *
 * <p>装配真实协作对象（{@link SplitEngine} + {@link ClearingService} + {@link ClearingInstructionService}
 * + {@link ChannelSplitPlanner} + {@link ClearingChannelMock}），仅把「账本 / 账户 / 仓储」替换为内存实现，
 * 从而在无外部依赖下验证 T07 的验收点：
 * <ol>
 *   <li>扫码购 $100 → <b>四腿</b>（平台 5 / 站 10 / 物流 5 / 厂家 80）逐腿过账，借贷守恒；</li>
 *   <li>四张 {@code clearing_instruction} 全部推进到 <b>SETTLED</b>；</li>
 *   <li>幂等：同 {@code basisRef} 重复触发不重复入账；</li>
 *   <li>三类降级：收款方未绑 ABA（保留 CREATED）、通道不支持（保留 CREATED）、通道拒付（落 FAILED），
 *       三者都<b>不回滚已提交的账本权益</b>（账本权益实时、通道动作异步）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class R1ConsignmentClearingFlowTest {

    private static final String SCENE = "CONSIGNMENT_SCAN";

    @Mock
    private SettlementRuleRepository settlementRuleRepository;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private AccountService accountService;
    @Mock
    private PaymentOrderRepository paymentOrderRepository;
    @Mock
    private ClearingInstructionRepository clearingInstructionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChannelSplitPlanner planner = new ChannelSplitPlanner();
    private final ClearingChannelMock gateway = new ClearingChannelMock();

    /** 内存指令表（替代 PG）。 */
    private final Map<Long, ClearingInstruction> store = new HashMap<>();
    private final AtomicLong idSeq = new AtomicLong();
    private final AtomicLong accountSeq = new AtomicLong(100);

    private ConsignmentClearingHandler handler;

    private static SettlementRule rule(String payee, RuleBasis basis, String rate, int priority) {
        return SettlementRule.builder()
                .payeeType(payee).basis(basis)
                .rate(rate == null ? null : new BigDecimal(rate))
                .priority(priority).currency("USD").settleCycle("T+0").status("ACTIVE").build();
    }

    private static PaymentOrder paidScanPurchase(String orderNo, String amount) {
        return PaymentOrder.builder()
                .orderNo(orderNo).bizRef("SCAN_PURCHASE:" + orderNo).amountUsd(new BigDecimal(amount))
                .channel("khqr").paymentMethod("SCAN_PURCHASE").status(PayStatus.PAID).build();
    }

    /** 常规解析器：平台自有方返回空（豁免），其余返回 ABA 账户号。 */
    private static PayeeAbaRefResolver resolver() {
        return (payeeType, currency) ->
                "PLATFORM".equals(payeeType) ? Optional.empty() : Optional.of("ABA-" + payeeType);
    }

    /** 构造被测链路（可指定解析器与通道集合）。 */
    private ConsignmentClearingHandler buildHandler(PayeeAbaRefResolver resolver,
                                                    List<ClearingChannelGateway> gateways) {
        SplitEngine splitEngine = new SplitEngine(settlementRuleRepository);
        ClearingInstructionService instructionService = new ClearingInstructionService(clearingInstructionRepository);
        ClearingService clearingService =
                new ClearingService(splitEngine, instructionService, ledgerService, accountService);
        return new ConsignmentClearingHandler(paymentOrderRepository, clearingService, instructionService,
                planner, resolver, gateways, objectMapper);
    }

    @BeforeEach
    void setUp() {
        store.clear();
        idSeq.set(0);
        accountSeq.set(100);
        gateway.reset();

        when(clearingInstructionRepository.save(any(ClearingInstruction.class))).thenAnswer(inv -> {
            ClearingInstruction instruction = inv.getArgument(0);
            if (instruction.getId() == null) {
                instruction.setId(idSeq.incrementAndGet());
            }
            store.put(instruction.getId(), instruction);
            return instruction;
        });
        when(clearingInstructionRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(clearingInstructionRepository.findByIdemKey(anyString())).thenAnswer(inv -> store.values().stream()
                .filter(c -> inv.getArgument(0).equals(c.getIdemKey())).findFirst());
        when(clearingInstructionRepository.findByBasisRef(anyString())).thenAnswer(inv -> store.values().stream()
                .filter(c -> inv.getArgument(0).equals(c.getBasisRef()))
                .sorted(Comparator.comparing(ClearingInstruction::getId))
                .toList());

        when(ledgerService.getPlatformAccountId()).thenReturn(1L);
        when(accountService.getOrCreatePlatformAccount(any(AccountType.class)))
                .thenAnswer(inv -> Account.builder().id(accountSeq.incrementAndGet()).build());

        when(settlementRuleRepository
                .findByBizSceneAndStatusAndDeletedFalseOrderByPriorityAsc(SCENE, "ACTIVE"))
                .thenReturn(List.of(
                        rule("PLATFORM", RuleBasis.RATE, "0.05", 1),
                        rule("STATION", RuleBasis.RATE, "0.10", 10),
                        rule("LOGISTICS", RuleBasis.RATE, "0.05", 20),
                        rule("MANUFACTURER", RuleBasis.RESIDUAL, null, 99)));

        handler = buildHandler(resolver(), List.of(gateway));
    }

    /** 取某收款方腿的指令（按 ledger_biz_ref 后缀）。 */
    private ClearingInstruction instructionOf(String payeeType) {
        return store.values().stream()
                .filter(c -> c.getLedgerBizRef() != null
                        && c.getLedgerBizRef().endsWith(":" + payeeType))
                .findFirst()
                .orElseThrow();
    }

    // ===================== 主路径 =====================

    @Test
    void scanPurchase100_postsFourLegs_andSettlesAllInstructions() {
        when(paymentOrderRepository.findByOrderNo("ORD-100"))
                .thenReturn(Optional.of(paidScanPurchase("ORD-100", "100.00")));

        handler.handle(1L, "{\"orderNo\":\"ORD-100\"}");

        // ① 四腿入账，金额守恒（平台 5 / 站 10 / 物流 5 / 厂家残差 80）
        assertEquals(4, store.size());
        assertAmount("PLATFORM", "5.0000");
        assertAmount("STATION", "10.0000");
        assertAmount("LOGISTICS", "5.0000");
        assertAmount("MANUFACTURER", "80.0000");
        BigDecimal sum = store.values().stream()
                .map(ClearingInstruction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(new BigDecimal("100.0000")));

        // ② 账本：唯一出口 postEntries，逐腿一笔，借平台对冲户 / 贷收款方子户（借贷守恒）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerRequests.Entry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerService, times(4)).postEntries(eq(BizType.CLEARING_SETTLE), anyString(), captor.capture());
        for (List<LedgerRequests.Entry> entries : captor.getAllValues()) {
            assertEquals(2, entries.size());
            assertEquals(1L, entries.get(0).accountId().longValue());
            assertEquals(LedgerRequests.Direction.D, entries.get(0).direction());
            assertEquals(0, entries.get(0).amount().compareTo(entries.get(1).amount()));
        }

        // ③ 指令全部 SETTLED（CREATED → SENT → ACKED → SETTLED）
        for (ClearingInstruction instruction : store.values()) {
            assertEquals(ClearingStatus.SETTLED, instruction.getStatus());
            assertNotNull(instruction.getInstitutionRef());
            assertNotNull(instruction.getSentAt());
            assertNotNull(instruction.getAckedAt());
            assertNotNull(instruction.getSettledAt());
            assertEquals("ABA_PAYWAY", instruction.getChannel());
        }

        // ④ 通道：单次下发、4 受益人、单分片
        assertEquals(1, gateway.invocations().size());
        assertEquals("ORD-100", gateway.invocations().get(0).ref());
        assertEquals(4, gateway.invocations().get(0).payees().size());
    }

    @Test
    void rerunSameBasisRef_isIdempotent_noDuplicatePosting() {
        when(paymentOrderRepository.findByOrderNo("ORD-100"))
                .thenReturn(Optional.of(paidScanPurchase("ORD-100", "100.00")));

        handler.handle(1L, "{\"orderNo\":\"ORD-100\"}");
        handler.handle(2L, "{\"orderNo\":\"ORD-100\"}");

        assertEquals(4, store.size(), "幂等重放不得新增指令");
        verify(ledgerService, times(4)).postEntries(eq(BizType.CLEARING_SETTLE), anyString(), any());
        assertEquals(1, gateway.invocations().size(), "幂等重放不得重复下发通道");
    }

    @Test
    void nonPaidOrder_isSkipped() {
        PaymentOrder created = paidScanPurchase("ORD-100", "100.00");
        created.setStatus(PayStatus.CREATED);
        when(paymentOrderRepository.findByOrderNo("ORD-100")).thenReturn(Optional.of(created));

        handler.handle(1L, "{\"orderNo\":\"ORD-100\"}");

        assertTrue(store.isEmpty());
        verify(ledgerService, times(0)).postEntries(any(), anyString(), any());
    }

    @Test
    void unknownOrder_isSkipped() {
        when(paymentOrderRepository.findByOrderNo("ORD-X")).thenReturn(Optional.empty());

        handler.handle(1L, "{\"orderNo\":\"ORD-X\"}");

        assertTrue(store.isEmpty());
    }

    @Test
    void malformedPayload_isSkipped() {
        handler.handle(1L, "not-json");

        assertTrue(store.isEmpty());
    }

    // ===================== 降级路径 B/C（账本必须保留）=====================

    @Test
    void payeeMissingAbaRef_keepsLedgerButLeavesInstructionsCreated() {
        PayeeAbaRefResolver noStation = (payeeType, currency) ->
                "STATION".equals(payeeType) ? Optional.empty() : resolver().resolve(payeeType, currency);
        ConsignmentClearingHandler degrading = buildHandler(noStation, List.of(gateway));
        when(paymentOrderRepository.findByOrderNo("ORD-100"))
                .thenReturn(Optional.of(paidScanPurchase("ORD-100", "100.00")));

        degrading.handle(1L, "{\"orderNo\":\"ORD-100\"}");

        // 账本权益实时（四腿已入账），通道动作未发生 → 指令保留 CREATED，待周期批量(T+7)代付
        verify(ledgerService, times(4)).postEntries(eq(BizType.CLEARING_SETTLE), anyString(), any());
        assertEquals(4, store.size());
        for (ClearingInstruction instruction : store.values()) {
            assertEquals(ClearingStatus.CREATED, instruction.getStatus());
        }
        assertTrue(gateway.invocations().isEmpty());
    }

    @Test
    void channelUnsupported_keepsLedgerButLeavesInstructionsCreated() {
        gateway.setSupported(false);
        when(paymentOrderRepository.findByOrderNo("ORD-100"))
                .thenReturn(Optional.of(paidScanPurchase("ORD-100", "100.00")));

        handler.handle(1L, "{\"orderNo\":\"ORD-100\"}");

        verify(ledgerService, times(4)).postEntries(eq(BizType.CLEARING_SETTLE), anyString(), any());
        assertTrue(store.values().stream().allMatch(i -> i.getStatus() == ClearingStatus.CREATED));
        assertTrue(gateway.invocations().isEmpty(), "通道不支持时不应发起下发");
    }

    @Test
    void channelRejects_marksInstructionsFailed_butKeepsLedger() {
        ClearingChannelGateway rejecting = new ClearingChannelGateway() {
            @Override
            public String channelCode() {
                return "ABA_PAYWAY";
            }

            @Override
            public boolean supports(ClearingMode mode) {
                return true;
            }

            @Override
            public SplitResult splitAtSource(String orderNo, BigDecimal amount,
                                             List<ChannelPayee> payees, String currency) {
                throw AbaClearingChannel.translateError("25", "beneficiary limit exceeded");
            }

            @Override
            public String payoutToPayee(String instructionNo, BigDecimal amount,
                                        String payeeAccountJson, String currency) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };
        ConsignmentClearingHandler rejectingHandler = buildHandler(resolver(), List.of(rejecting));
        when(paymentOrderRepository.findByOrderNo("ORD-100"))
                .thenReturn(Optional.of(paidScanPurchase("ORD-100", "100.00")));

        rejectingHandler.handle(1L, "{\"orderNo\":\"ORD-100\"}");

        // 账本已提交（不回滚），指令落 FAILED 作「挂起记录」，等人工修复或批量代付
        verify(ledgerService, times(4)).postEntries(eq(BizType.CLEARING_SETTLE), anyString(), any());
        assertEquals(4, store.size());
        for (ClearingInstruction instruction : store.values()) {
            assertEquals(ClearingStatus.FAILED, instruction.getStatus());
            assertNotNull(instruction.getFailReason());
        }
    }

    @Test
    void noChannelImplementation_degradesWithoutTouchingLedger() {
        ConsignmentClearingHandler noChannel = buildHandler(resolver(), List.of());
        when(paymentOrderRepository.findByOrderNo("ORD-100"))
                .thenReturn(Optional.of(paidScanPurchase("ORD-100", "100.00")));

        noChannel.handle(1L, "{\"orderNo\":\"ORD-100\"}");

        assertTrue(store.isEmpty());
        verify(ledgerService, times(0)).postEntries(any(), anyString(), any());
    }

    @Test
    void abaErrorCodeMapping_isExposedForRealIntegration() {
        assertEquals("error.clearing.channel.split.total.mismatch", AbaClearingChannel.errorKeyOf("92"));
        assertEquals("error.clearing.channel.beneficiary.limit", AbaClearingChannel.errorKeyOf("25"));
        assertEquals("error.clearing.channel.limit.exceeded", AbaClearingChannel.errorKeyOf("70"));
        assertEquals("error.clearing.channel.limit.exceeded", AbaClearingChannel.errorKeyOf("LAM01"));
        assertEquals("error.clearing.channel.limit.exceeded", AbaClearingChannel.errorKeyOf("LAM02"));
        assertEquals("error.clearing.channel.system.error", AbaClearingChannel.errorKeyOf("6"));
        assertEquals("error.clearing.channel.code.unknown", AbaClearingChannel.errorKeyOf("9999"));

        BizException translated = AbaClearingChannel.translateError("92", "split total mismatch");
        assertEquals(AbaClearingChannel.CODE_SPLIT_TOTAL_MISMATCH, translated.getCode());
    }

    @Test
    void abaSkeleton_throwsNotImplemented() {
        AbaClearingChannel aba = new AbaClearingChannel();

        assertTrue(aba.supports(ClearingMode.AT_SOURCE));
        assertTrue(aba.supports(ClearingMode.ON_ARRIVAL));
        assertFalse(aba.supports(ClearingMode.BATCH));

        BizException ex = org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                () -> aba.splitAtSource("ORD-1", new BigDecimal("1.00"), List.of(), "USD"));
        assertEquals(42275, ex.getCode());
    }

    /** 断言某收款方腿的指令金额。 */
    private void assertAmount(String payeeType, String expected) {
        assertEquals(0, instructionOf(payeeType).getAmount().compareTo(new BigDecimal(expected)));
    }
}
