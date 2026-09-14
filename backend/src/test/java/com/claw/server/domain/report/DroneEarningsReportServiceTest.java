package com.claw.server.domain.report;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.CapacityType;
import com.claw.server.common.enums.DroneMissionType;
import com.claw.server.common.enums.RebateStatus;
import com.claw.server.common.enums.TaskStatus;
import com.claw.server.common.enums.TaskType;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.capacity.CapacityPlan;
import com.claw.server.domain.capacity.CapacityPlanRepository;
import com.claw.server.domain.capacity.CapacityRebateSettlement;
import com.claw.server.domain.capacity.CapacityRebateSettlementRepository;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.payload.DroneMission;
import com.claw.server.domain.payload.DroneMissionRepository;
import com.claw.server.domain.task.Task;
import com.claw.server.domain.task.TaskAssignment;
import com.claw.server.domain.task.TaskAssignmentRepository;
import com.claw.server.domain.task.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DroneEarningsReportService 单元测试（纯 Mockito，不连 DB / 不加载 Spring）。
 *
 * <p>校验口径：任务收益只统计 SETTLED 接单 + DRONE_OP 任务 + 结算时刻落窗 +
 * 记在接单方主账户的 C 分录（平台佣金不误报）；容量回佣只统计 PARALLEL 计划下
 * SETTLED 且落窗的回佣明细；总览跨资产聚合与单资产同口径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class DroneEarningsReportServiceTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-12-31T23:59:59Z");
    private static final Instant IN_WINDOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Instant OUT_OF_WINDOW = Instant.parse("2025-06-01T12:00:00Z");

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private DroneMissionRepository droneMissionRepository;

    @Mock
    private TaskAssignmentRepository taskAssignmentRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private CapacityPlanRepository capacityPlanRepository;

    @Mock
    private CapacityRebateSettlementRepository rebateSettlementRepository;

    @InjectMocks
    private DroneEarningsReportService service;

    // ------------------------------------------------------------------ 单资产

    @Test
    void generate_aggregatesTaskByMissionType_andRebateByPlan() {
        stubDroneAsset(46L);
        when(droneMissionRepository.findByAssetId(46L)).thenReturn(List.of(
                mission(900L, 46L, DroneMissionType.SPRAY),
                mission(901L, 46L, DroneMissionType.CARGO)));
        when(taskAssignmentRepository.findByAssetId(46L)).thenReturn(List.of(
                assignment(1L, 11L, 7L, TaskStatus.SETTLED),      // 计入（SPRAY）
                assignment(2L, 12L, 7L, TaskStatus.SETTLED),      // 结算时刻出窗 → 跳过
                assignment(3L, 13L, 7L, TaskStatus.COMPLETED)));  // 未结算 → 跳过

        Task t11 = task(11L, 900L, IN_WINDOW);
        Task t12 = task(12L, 901L, OUT_OF_WINDOW);
        when(taskRepository.findById(11L)).thenReturn(Optional.of(t11));
        when(taskRepository.findById(12L)).thenReturn(Optional.of(t12));
        // assignment 3 未结算，不应触发任务查询；此处不 stub findById(13) 以验证口径。

        Account providerAcct = mockAccount(77L);
        when(accountService.findUserAccount(7L)).thenReturn(Optional.of(providerAcct));
        when(accountService.findEntriesByBizRef("TASK-11-1")).thenReturn(List.of(
                entry(77L, "C", "80.005"),     // 接单方收益（计入）
                entry(77L, "D", "100.00"),     // 发布方出账（方向不符）
                entry(999L, "C", "20.00")));   // 平台佣金（账户不符，不得误报）

        // 容量回佣：PARALLEL 计划计入，SERIAL 计划跳过
        when(capacityPlanRepository.findByAssetIdAndDeletedFalse(46L)).thenReturn(List.of(
                plan(5L, 46L, CapacityType.PARALLEL),
                plan(6L, 46L, CapacityType.SERIAL)));
        when(rebateSettlementRepository.findByPlanIdAndDeletedFalse(5L)).thenReturn(List.of(
                rebate("RB-1", 5L, "12.50", IN_WINDOW, RebateStatus.SETTLED),   // 计入
                rebate("RB-2", 5L, "8.00", OUT_OF_WINDOW, RebateStatus.SETTLED), // 出窗
                rebate("RB-3", 5L, "9.00", IN_WINDOW, RebateStatus.PENDING)));   // 未结算
        // plan 6 是 SERIAL，不应触发回佣查询；此处不 stub 以验证口径。

        DroneEarningsReport report = service.generate(46L, FROM, TO);

        // 明细行：TASK:SPRAY(80.01) + REBATE:plan-5(12.50)
        assertEquals(2, report.getLines().size());
        DroneEarningsReport.EarningsLine spray = findLine(report, "TASK_SETTLEMENT", "SPRAY");
        assertEquals(1L, spray.getCount());
        assertEquals(0, spray.getAmount().compareTo(new BigDecimal("80.01"))); // 80.005 HALF_UP → 80.01

        DroneEarningsReport.EarningsLine rebate = findLine(report, "CAPACITY_REBATE", "plan-5");
        assertEquals(1L, rebate.getCount());
        assertEquals(0, rebate.getAmount().compareTo(new BigDecimal("12.50")));

        assertEquals(0, report.getGrossTotal().compareTo(new BigDecimal("92.51")));
        assertEquals(0, report.getCapacityUserShare().compareTo(new BigDecimal("12.50")));
        assertEquals(0, report.getPlatformShare().compareTo(new BigDecimal("80.01")));
        assertEquals("USD", report.getCurrency());
        assertEquals(46L, report.getAssetId());
    }

    @Test
    void generate_skipsAssignmentWhenProviderAccountMissing() {
        stubDroneAsset(46L);
        when(droneMissionRepository.findByAssetId(46L)).thenReturn(List.of());
        when(taskAssignmentRepository.findByAssetId(46L)).thenReturn(List.of(
                assignment(1L, 11L, 7L, TaskStatus.SETTLED)));
        Task t11 = task(11L, 900L, IN_WINDOW);
        when(taskRepository.findById(11L)).thenReturn(Optional.of(t11));
        when(accountService.findUserAccount(7L)).thenReturn(Optional.empty());

        DroneEarningsReport report = service.generate(46L, FROM, TO);

        assertEquals(0, report.getLines().size());
        assertEquals(0, report.getGrossTotal().compareTo(BigDecimal.ZERO));
        assertEquals(0, report.getCapacityUserShare().compareTo(BigDecimal.ZERO));
        assertEquals(0, report.getPlatformShare().compareTo(BigDecimal.ZERO));
    }

    @Test
    void generate_unknownMissionType_whenTaskHasNoMissionLink() {
        stubDroneAsset(46L);
        when(droneMissionRepository.findByAssetId(46L)).thenReturn(List.of());
        when(taskAssignmentRepository.findByAssetId(46L)).thenReturn(List.of(
                assignment(1L, 11L, 7L, TaskStatus.SETTLED)));
        Task t11 = task(11L, null, IN_WINDOW); // droneMissionId 为 null
        when(taskRepository.findById(11L)).thenReturn(Optional.of(t11));
        Account providerAcct = mockAccount(77L);
        when(accountService.findUserAccount(7L)).thenReturn(Optional.of(providerAcct));
        when(accountService.findEntriesByBizRef("TASK-11-1")).thenReturn(List.of(
                entry(77L, "C", "50.00")));

        DroneEarningsReport report = service.generate(46L, FROM, TO);

        DroneEarningsReport.EarningsLine unknown = findLine(report, "TASK_SETTLEMENT", "UNKNOWN");
        assertEquals(0, unknown.getAmount().compareTo(new BigDecimal("50.00")));
        assertEquals(0, report.getGrossTotal().compareTo(new BigDecimal("50.00")));
    }

    @Test
    void generate_rejectsInvalidWindow_withInvalidParam() {
        BizException ex = assertThrows(BizException.class,
                () -> service.generate(46L, TO, FROM)); // from 晚于 to
        assertEquals(BizException.INVALID_PARAM, ex.getCode());

        BizException ex2 = assertThrows(BizException.class,
                () -> service.generate(46L, null, TO)); // 缺 from
        assertEquals(BizException.INVALID_PARAM, ex2.getCode());
    }

    @Test
    void generate_assetNotFound_throwsNotFound() {
        when(assetRepository.findById(46L)).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class,
                () -> service.generate(46L, FROM, TO));
        assertEquals(BizException.NOT_FOUND, ex.getCode());
    }

    @Test
    void generate_nonDroneAsset_throwsNotFound() {
        when(assetRepository.findById(46L)).thenReturn(Optional.of(
                Asset.builder().id(46L).assetType(AssetType.BATTERY).build()));
        BizException ex = assertThrows(BizException.class,
                () -> service.generate(46L, FROM, TO));
        assertEquals(BizException.NOT_FOUND, ex.getCode());
    }

    // ------------------------------------------------------------------ 总览

    @Test
    void overview_aggregatesAcrossDroneAssets_withSameRules() {
        Asset drone46 = Asset.builder().id(46L).assetType(AssetType.DRONE).build();
        Asset drone47 = Asset.builder().id(47L).assetType(AssetType.DRONE).build();
        when(assetRepository.findByAssetTypeAndDeletedFalse(AssetType.DRONE))
                .thenReturn(List.of(drone46, drone47));

        // 资产 46：任务报酬 100.00（SPRAY）
        when(droneMissionRepository.findByAssetId(46L)).thenReturn(List.of(
                mission(900L, 46L, DroneMissionType.SPRAY)));
        when(taskAssignmentRepository.findByAssetId(46L)).thenReturn(List.of(
                assignment(1L, 11L, 7L, TaskStatus.SETTLED)));
        when(taskRepository.findById(11L)).thenReturn(Optional.of(task(11L, 900L, IN_WINDOW)));
        Account providerAcct = mockAccount(77L);
        when(accountService.findUserAccount(7L)).thenReturn(Optional.of(providerAcct));
        when(accountService.findEntriesByBizRef("TASK-11-1")).thenReturn(List.of(
                entry(77L, "C", "100.00")));

        // 资产 47：容量回佣 30.00（PARALLEL plan-9）
        when(droneMissionRepository.findByAssetId(47L)).thenReturn(List.of());
        when(taskAssignmentRepository.findByAssetId(47L)).thenReturn(List.of());
        when(capacityPlanRepository.findByAssetIdAndDeletedFalse(46L)).thenReturn(List.of());
        when(capacityPlanRepository.findByAssetIdAndDeletedFalse(47L)).thenReturn(List.of(
                plan(9L, 47L, CapacityType.PARALLEL)));
        when(rebateSettlementRepository.findByPlanIdAndDeletedFalse(9L)).thenReturn(List.of(
                rebate("RB-9", 9L, "30.00", IN_WINDOW, RebateStatus.SETTLED)));

        DroneEarningsReport.Overview overview = service.overview(FROM, TO);

        assertEquals(2L, overview.getAssetCount());
        assertEquals(0, overview.getTaskEarningsTotal().compareTo(new BigDecimal("100.00")));
        assertEquals(0, overview.getRebateTotal().compareTo(new BigDecimal("30.00")));
        assertEquals(0, overview.getGrossTotal().compareTo(new BigDecimal("130.00")));

        // 明细行两行：TASK:SPRAY(100.00) + REBATE:plan-9(30.00)
        assertEquals(2, overview.getLines().size());
        assertEquals(0, findLine(overview.getLines(), "TASK_SETTLEMENT", "SPRAY")
                .getAmount().compareTo(new BigDecimal("100.00")));
        assertEquals(0, findLine(overview.getLines(), "CAPACITY_REBATE", "plan-9")
                .getAmount().compareTo(new BigDecimal("30.00")));

        // 分资产小结：两个资产都有收益
        assertEquals(2, overview.getByAsset().size());
        assertTrue(overview.getByAsset().stream()
                .anyMatch(s -> s.getAssetId().equals(46L)
                        && s.getTaskEarningsTotal().compareTo(new BigDecimal("100.00")) == 0));
        assertTrue(overview.getByAsset().stream()
                .anyMatch(s -> s.getAssetId().equals(47L)
                        && s.getRebateTotal().compareTo(new BigDecimal("30.00")) == 0));
        assertEquals("USD", overview.getCurrency());
    }

    @Test
    void overview_rejectsInvalidWindow() {
        BizException ex = assertThrows(BizException.class, () -> service.overview(TO, FROM));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }

    @Test
    void overview_emptyAssetPool_returnsZeros() {
        when(assetRepository.findByAssetTypeAndDeletedFalse(AssetType.DRONE))
                .thenReturn(List.of());
        DroneEarningsReport.Overview overview = service.overview(FROM, TO);
        assertEquals(0L, overview.getAssetCount());
        assertEquals(0, overview.getGrossTotal().compareTo(BigDecimal.ZERO));
        assertEquals(0, overview.getLines().size());
        assertEquals(0, overview.getByAsset().size());
    }

    // ------------------------------------------------------------------ 夹具

    private void stubDroneAsset(Long assetId) {
        lenient().when(assetRepository.findById(assetId)).thenReturn(Optional.of(
                Asset.builder().id(assetId).assetType(AssetType.DRONE).build()));
    }

    private DroneMission mission(Long id, Long assetId, DroneMissionType type) {
        return DroneMission.builder()
                .id(id)
                .assetId(assetId)
                .missionType(type)
                .pilotId(7L)
                .executedAt(IN_WINDOW)
                .build();
    }

    private TaskAssignment assignment(Long id, Long taskId, Long providerId, TaskStatus status) {
        return TaskAssignment.builder()
                .id(id)
                .taskId(taskId)
                .providerId(providerId)
                .assetId(46L)
                .status(status)
                .build();
    }

    private Task task(Long id, Long droneMissionId, Instant settledAt) {
        Task t = Task.builder()
                .id(id)
                .publisherId(9L)
                .taskType(TaskType.DRONE_OP)
                .title("t")
                .rewardAmount(new BigDecimal("100.0000"))
                .capabilityRequired(com.claw.server.common.enums.AssetCapability.DRONE_OP)
                .droneMissionId(droneMissionId)
                .build();
        t.setSettledAt(settledAt);
        return t;
    }

    private CapacityPlan plan(Long id, Long assetId, CapacityType type) {
        return CapacityPlan.builder()
                .id(id)
                .assetId(assetId)
                .ownerUserId(8L)
                .totalUnits(10)
                .unitPrice(new BigDecimal("10.00"))
                .capacityType(type)
                .build();
    }

    private CapacityRebateSettlement rebate(String no, Long planId, String amount,
                                            Instant createdAt, RebateStatus status) {
        return CapacityRebateSettlement.builder()
                .settlementNo(no)
                .planId(planId)
                .ownerShareBase(new BigDecimal("100.00"))
                .rebateTotal(new BigDecimal(amount))
                .subscriberUserId(99L)
                .unitCount(1)
                .ratio(new BigDecimal("0.10"))
                .amount(new BigDecimal(amount))
                .status(status)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private Account mockAccount(Long id) {
        Account account = mock(Account.class);
        lenient().when(account.getId()).thenReturn(id);
        return account;
    }

    private LedgerViews.EntryView entry(Long accountId, String direction, String amount) {
        return new LedgerViews.EntryView(1L, UUID.randomUUID(), accountId, direction,
                new BigDecimal(amount), "TASK_SETTLEMENT", "TASK-11-1", "memo", IN_WINDOW);
    }

    private DroneEarningsReport.EarningsLine findLine(DroneEarningsReport report,
                                                      String source, String subtype) {
        return findLine(report.getLines(), source, subtype);
    }

    private DroneEarningsReport.EarningsLine findLine(List<DroneEarningsReport.EarningsLine> lines,
                                                      String source, String subtype) {
        return lines.stream()
                .filter(l -> source.equals(l.getSource()) && subtype.equals(l.getSubtype()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing line " + source + ":" + subtype + " in " + lines));
    }
}
