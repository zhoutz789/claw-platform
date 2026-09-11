package com.claw.server.domain.task;

import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.dto.TaskViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.TaskStatus;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountEntry;
import com.claw.server.domain.ledger.AccountEntryRepository;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskSettlementService 闭环单元测试（无 DB，Mockito）。
 *
 * <p>覆盖两条主线：
 * <ul>
 *   <li>零费率（TASK_HALL_PLATFORM_RATE 缺省/非法）：settle() 恰好提交 2 条平衡分录
 *       publisher D reward / provider C reward —— 这是「默认 0 费率行为不变」的回归证明；</li>
 *   <li>非零费率：按 2 位精度拆出平台佣金，3 条分录严格平衡，且接单方所得 = reward - commission。</li>
 * </ul>
 *
 * <p>资产收益对账（assetEarnings）须按账户过滤，把平台佣金分录排除在接单方收益之外。
 */
@ExtendWith(MockitoExtension.class)
class TaskSettlementServiceTest {

    private static final String RATE_KEY = "TASK_HALL_PLATFORM_RATE";

    @Mock
    private AccountService accountService;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private TaskAssignmentRepository taskAssignmentRepository;
    @Mock
    private AccountEntryRepository accountEntryRepository;
    @Mock
    private AssetRepository assetRepository;
    @Mock
    private SystemConfigRepository systemConfigRepository;

    @InjectMocks
    private TaskSettlementService settlementService;

    // =====================================================================
    //  零费率路径：配置缺省 → 行为与历史完全一致（恰好 2 条分录）
    // =====================================================================

    @Test
    void settle_postsDoubleEntryForLogisticsClosedLoop() {
        Long publisherId = 1L;
        Long providerId = 2L;
        Long taskId = 7L;
        Long assignmentId = 5L;

        Account publisherAcct = Account.builder().id(10L).userId(publisherId).build();
        Account providerAcct = Account.builder().id(20L).userId(providerId).build();
        when(accountService.getOrCreateUserAccount(publisherId)).thenReturn(publisherAcct);
        when(accountService.getOrCreateUserAccount(providerId)).thenReturn(providerAcct);

        Task task = Task.builder()
                .id(taskId)
                .publisherId(publisherId)
                .rewardAmount(new BigDecimal("5.00"))
                .status(TaskStatus.COMPLETED)
                .build();
        TaskAssignment assignment = TaskAssignment.builder()
                .id(assignmentId)
                .taskId(taskId)
                .providerId(providerId)
                .assetId(99L)
                .status(TaskStatus.COMPLETED)
                .build();

        settlementService.settle(task, assignment);

        ArgumentCaptor<BizType> bizTypeCaptor = ArgumentCaptor.forClass(BizType.class);
        ArgumentCaptor<String> bizRefCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<LedgerRequests.Entry>> entriesCaptor = entriesCaptor();

        verify(ledgerService, times(1))
                .postEntries(bizTypeCaptor.capture(), bizRefCaptor.capture(), entriesCaptor.capture());

        assertEquals(BizType.TASK_SETTLEMENT, bizTypeCaptor.getValue());
        assertEquals("TASK-7-5", bizRefCaptor.getValue());

        List<LedgerRequests.Entry> entries = entriesCaptor.getValue();
        assertEquals(2, entries.size());

        LedgerRequests.Entry debit = entries.get(0);
        assertEquals(LedgerRequests.Direction.D, debit.direction());
        assertEquals(0, new BigDecimal("5.00").compareTo(debit.amount()));
        assertEquals(10L, debit.accountId());

        LedgerRequests.Entry credit = entries.get(1);
        assertEquals(LedgerRequests.Direction.C, credit.direction());
        assertEquals(0, new BigDecimal("5.00").compareTo(credit.amount()));
        assertEquals(20L, credit.accountId());

        // 服务内同步将任务置为 SETTLED
        assertEquals(TaskStatus.SETTLED, task.getStatus());
    }

    // =====================================================================
    //  非零费率：拆出平台佣金，3 条分录严格平衡
    // =====================================================================

    @Test
    void settle_withTenPercentPlatformRate_postsThreeBalancedEntries() {
        stubRate("0.10");
        stubUserAccounts(1L, 2L, 10L, 20L);
        when(accountService.getOrCreatePlatformAccount(AccountType.MASTER))
                .thenReturn(Account.builder().id(30L).accountType(AccountType.MASTER).build());

        settlementService.settle(task("5.00"), assignment());

        List<LedgerRequests.Entry> entries = capturedEntries();
        assertEquals(3, entries.size());

        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        BigDecimal providerCredit = null;
        BigDecimal platformCredit = null;
        for (LedgerRequests.Entry e : entries) {
            if (e.direction() == LedgerRequests.Direction.D) {
                debit = debit.add(e.amount());
            } else {
                credit = credit.add(e.amount());
                if (e.accountId() == 20L) {
                    providerCredit = e.amount();
                } else if (e.accountId() == 30L) {
                    platformCredit = e.amount();
                }
            }
        }

        assertEquals(0, debit.compareTo(credit), "借贷必须精确平衡");
        assertEquals(0, new BigDecimal("5.00").compareTo(debit), "发布方全额出账 reward");
        assertNotNull(providerCredit, "接单方须有一条 C 分录");
        assertEquals(0, new BigDecimal("4.50").compareTo(providerCredit), "接单方所得 = reward - commission");
        assertNotNull(platformCredit, "平台须有一条佣金 C 分录");
        assertEquals(0, new BigDecimal("0.50").compareTo(platformCredit), "平台佣金 = reward * 0.10");
    }

    /**
     * 舍入陷阱：reward=0.05、rate=0.10。
     *
     * <p>commission = round(0.05 * 0.10) = round(0.005) = 0.01（HALF_UP）；
     * provider = 0.05 - 0.01 = 0.04。若改用 {@code reward * (1 - rate)} 会得到 round(0.045)=0.05，
     * 与 commission 相加超出发布方的 0.05 → 借贷不平衡（42250）。故必须按减法拆账。
     */
    @Test
    void settle_roundingEdge_smallReward_threeAmountsStayExactlyBalanced() {
        stubRate("0.10");
        stubUserAccounts(1L, 2L, 10L, 20L);
        when(accountService.getOrCreatePlatformAccount(AccountType.MASTER))
                .thenReturn(Account.builder().id(30L).accountType(AccountType.MASTER).build());

        settlementService.settle(task("0.05"), assignment());

        List<LedgerRequests.Entry> entries = capturedEntries();
        assertEquals(3, entries.size());

        LedgerRequests.Entry publisher = entries.get(0);
        LedgerRequests.Entry provider = entries.get(1);
        LedgerRequests.Entry platform = entries.get(2);

        assertEquals(0, new BigDecimal("0.05").compareTo(publisher.amount()));
        assertEquals(0, new BigDecimal("0.04").compareTo(provider.amount()));
        assertEquals(0, new BigDecimal("0.01").compareTo(platform.amount()));
        assertEquals(0, publisher.amount().compareTo(provider.amount().add(platform.amount())),
                "2 位精度下三条金额必须严格平衡");
    }

    // =====================================================================
    //  非法费率：一律按 0 处理（恰好 2 条分录）
    // =====================================================================

    @Test
    void settle_invalidRateNonNumeric_treatedAsZero() {
        assertInvalidRateYieldsTwoEntries("abc");
    }

    @Test
    void settle_invalidRateNegative_treatedAsZero() {
        assertInvalidRateYieldsTwoEntries("-0.1");
    }

    @Test
    void settle_invalidRateGreaterThanOne_treatedAsZero() {
        assertInvalidRateYieldsTwoEntries("1.5");
    }

    @Test
    void settle_invalidRateBlank_treatedAsZero() {
        assertInvalidRateYieldsTwoEntries("");
    }

    private void assertInvalidRateYieldsTwoEntries(String rawValue) {
        stubRate(rawValue);
        stubUserAccounts(1L, 2L, 10L, 20L);

        settlementService.settle(task("5.00"), assignment());

        List<LedgerRequests.Entry> entries = capturedEntries();
        assertEquals(2, entries.size(), "非法费率须按 0 处理，仅 2 条分录");
        LedgerRequests.Entry credit = entries.get(1);
        assertEquals(LedgerRequests.Direction.C, credit.direction());
        assertEquals(0, new BigDecimal("5.00").compareTo(credit.amount()), "接单方仍得全额");
    }

    // =====================================================================
    //  assetEarnings：必须排除平台佣金分录
    // =====================================================================

    @Test
    void assetEarnings_excludesPlatformCommissionEntry_keepsProviderOwnCredit() {
        Long assetId = 99L;
        Long providerId = 2L;

        when(assetRepository.findById(assetId))
                .thenReturn(Optional.of(Asset.builder().id(assetId).userId(providerId).build()));
        when(taskAssignmentRepository.findByAssetId(assetId)).thenReturn(List.of(taskAssignmentSettled(assetId, providerId)));
        when(accountService.findUserAccount(providerId))
                .thenReturn(Optional.of(Account.builder().id(20L).userId(providerId).build()));

        String bizRef = "TASK-7-5";
        Instant now = Instant.now();
        when(accountService.findEntriesByBizRef(bizRef)).thenReturn(List.of(
                new LedgerViews.EntryView(901L, null, 20L, "C", new BigDecimal("4.50"),
                        "TASK_SETTLEMENT", bizRef, "任务报酬收入 " + bizRef, now),
                new LedgerViews.EntryView(902L, null, 30L, "C", new BigDecimal("0.50"),
                        "TASK_SETTLEMENT", bizRef, "平台佣金 " + bizRef, now)));

        List<TaskViews.TaskEarningView> earnings = settlementService.assetEarnings(assetId, providerId);

        assertEquals(1, earnings.size(), "平台佣金分录不得计入接单方资产收益");
        TaskViews.TaskEarningView earning = earnings.get(0);
        assertEquals(7L, earning.taskId());
        assertEquals(5L, earning.assignmentId());
        assertEquals(assetId, earning.assetId());
        assertEquals(0, new BigDecimal("4.50").compareTo(earning.amount()));
    }

    // =====================================================================
    //  helpers
    // =====================================================================

    private void stubRate(String rawValue) {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(RATE_KEY))
                .thenReturn(Optional.of(SystemConfig.builder().configValue(rawValue).build()));
    }

    private void stubUserAccounts(Long publisherId, Long providerId, Long publisherAcctId, Long providerAcctId) {
        when(accountService.getOrCreateUserAccount(publisherId))
                .thenReturn(Account.builder().id(publisherAcctId).userId(publisherId).build());
        when(accountService.getOrCreateUserAccount(providerId))
                .thenReturn(Account.builder().id(providerAcctId).userId(providerId).build());
    }

    private static Task task(String reward) {
        return Task.builder()
                .id(7L)
                .publisherId(1L)
                .rewardAmount(new BigDecimal(reward))
                .status(TaskStatus.COMPLETED)
                .build();
    }

    private static TaskAssignment assignment() {
        return TaskAssignment.builder()
                .id(5L)
                .taskId(7L)
                .providerId(2L)
                .assetId(99L)
                .status(TaskStatus.COMPLETED)
                .build();
    }

    private static TaskAssignment taskAssignmentSettled(Long assetId, Long providerId) {
        return TaskAssignment.builder()
                .id(5L)
                .taskId(7L)
                .providerId(providerId)
                .assetId(assetId)
                .status(TaskStatus.SETTLED)
                .build();
    }

    private List<LedgerRequests.Entry> capturedEntries() {
        ArgumentCaptor<List<LedgerRequests.Entry>> captor = entriesCaptor();
        verify(ledgerService, times(1)).postEntries(any(BizType.class), anyString(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<LedgerRequests.Entry>> entriesCaptor() {
        return (ArgumentCaptor<List<LedgerRequests.Entry>>) (Object) ArgumentCaptor.forClass(List.class);
    }
}
