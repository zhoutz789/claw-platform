package com.claw.server.domain.task;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.dto.TaskRequests;
import com.claw.server.common.dto.TaskViews;
import com.claw.server.common.enums.AssetCapability;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.DroneMissionType;
import com.claw.server.common.enums.TaskStatus;
import com.claw.server.common.enums.TaskType;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import com.claw.server.domain.payload.DroneMission;
import com.claw.server.domain.payload.DroneMissionRepository;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 任务大厅「发布 → 接单 → 进度 → 完成 → 结算 → 资产收益对账」全链路服务层验证（纯 Mockito，不连库）。
 *
 * <p>{@link TaskService} 与 {@link TaskSettlementService} 均为真实对象（仅仓储 / AccountService /
 * LedgerService 为 mock），因此能真实暴露「结算后 assignment 未置 SETTLED → 资产收益恒为空」这类跨对象
 * 状态不一致：该 bug 下单看 ledger 分录完全正确，只有跑通链路才能发现。
 *
 * <p>核心回归断言：{@code complete()} 之后 task 与 assignment 双双为 SETTLED，
 * 且 {@code assetEarnings()} 能反查到 1 条收益（修复前为 0 条）。
 */
@ExtendWith(MockitoExtension.class)
class TaskHallSettlementFlowTest {

    private static final Long PUBLISHER_ID = 1L;
    private static final Long PROVIDER_ID = 2L;
    private static final Long ASSET_ID = 55L;
    private static final Long TASK_ID = 10L;
    private static final Long ASSIGNMENT_ID = 5L;
    private static final BigDecimal REWARD = new BigDecimal("120.00");

    @Mock
    private AccountService accountService;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private TaskAssignmentRepository taskAssignmentRepository;
    @Mock
    private AssetRepository assetRepository;

    @Mock
    private TaskLogisticsRepository taskLogisticsRepository;
    @Mock
    private TaskRideRepository taskRideRepository;
    @Mock
    private TaskAdRepository taskAdRepository;
    @Mock
    private DroneMissionRepository droneMissionRepository;

    /** 真实结算服务（@InjectMocks 注入其自身 5 个依赖）。 */
    @InjectMocks
    private TaskSettlementService settlementService;

    /** 真实任务服务，依赖上面那个【真实】settlementService —— 不做 mock，否则链路断裂。 */
    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(taskRepository, taskAssignmentRepository, taskLogisticsRepository,
                taskRideRepository, taskAdRepository, assetRepository, settlementService, droneMissionRepository);
    }

    /**
     * 主链路：publish(DRONE_OP) → accept → updateProgress → complete(settle) → assetEarnings。
     * 覆盖本次 bug 的回归断言：结算后 task 与 assignment 均 SETTLED，且资产收益可查到 1 条。
     */
    @Test
    void fullFlow_publishAcceptProgressComplete_thenEarningsVisible() {
        // ---------- 1. publish(DRONE_OP)：落库并回写 droneMissionId ----------
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(TASK_ID);
            }
            return t;
        });
        DroneMission[] missionHolder = new DroneMission[1];
        when(droneMissionRepository.save(any(DroneMission.class))).thenAnswer(inv -> {
            DroneMission m = inv.getArgument(0);
            m.setId(77L);
            missionHolder[0] = m;
            return m;
        });
        when(droneMissionRepository.findById(77L)).thenAnswer(inv -> Optional.ofNullable(missionHolder[0]));

        TaskViews.TaskView published = taskService.publish(droneReq(), PUBLISHER_ID);

        ArgumentCaptor<Task> taskSaveCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository, times(2)).save(taskSaveCaptor.capture());
        Task task = taskSaveCaptor.getValue();
        assertEquals(Long.valueOf(77L), task.getDroneMissionId(), "DRONE_OP 须回写 droneMissionId");
        assertEquals(TASK_ID, task.getId());
        assertEquals(TaskStatus.OPEN, task.getStatus());
        assertEquals(Long.valueOf(77L), published.droneMissionId());

        // ---------- 2. accept：assignment 落库，双方转 ASSIGNED ----------
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(assetRepository.findById(ASSET_ID)).thenReturn(asset());
        when(taskAssignmentRepository.save(any(TaskAssignment.class))).thenAnswer(inv -> {
            TaskAssignment a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(ASSIGNMENT_ID);
            }
            return a;
        });

        TaskViews.AssignmentView accepted = taskService.accept(TASK_ID, PROVIDER_ID, ASSET_ID);

        ArgumentCaptor<TaskAssignment> assignmentSaveCaptor = ArgumentCaptor.forClass(TaskAssignment.class);
        verify(taskAssignmentRepository, atLeastOnce()).save(assignmentSaveCaptor.capture());
        TaskAssignment assignment = assignmentSaveCaptor.getValue();
        assertEquals(ASSIGNMENT_ID, assignment.getId());
        assertEquals(TaskStatus.ASSIGNED, assignment.getStatus());
        assertEquals(TaskStatus.ASSIGNED, task.getStatus());
        assertEquals(TaskStatus.ASSIGNED, accepted.status());

        // ---------- 3. updateProgress：双方转 IN_PROGRESS ----------
        when(taskAssignmentRepository.findByTaskIdAndProviderId(TASK_ID, PROVIDER_ID))
                .thenReturn(Optional.of(assignment));

        TaskViews.AssignmentView inProgress =
                taskService.updateProgress(TASK_ID, PROVIDER_ID, new TaskRequests.Progress(50, "half way"));

        assertEquals(TaskStatus.IN_PROGRESS, assignment.getStatus());
        assertEquals(TaskStatus.IN_PROGRESS, task.getStatus());
        assertEquals(50, assignment.getProgressPct().intValue());
        assertEquals(TaskStatus.IN_PROGRESS, inProgress.status());

        // ---------- 4. complete → settle：task 与 assignment 双双 SETTLED ----------
        when(accountService.getOrCreateUserAccount(PUBLISHER_ID))
                .thenReturn(Account.builder().id(10L).userId(PUBLISHER_ID).build());
        when(accountService.getOrCreateUserAccount(PROVIDER_ID))
                .thenReturn(Account.builder().id(20L).userId(PROVIDER_ID).build());

        TaskViews.AssignmentView completed = taskService.complete(TASK_ID, PROVIDER_ID);
        assertEquals(TaskStatus.SETTLED, completed.status(), "完成返回的视图须反映最终 SETTLED 状态");
        assertNotNull(completed.finishedAt(), "complete() 须回填 finishedAt");

        assertEquals(TaskStatus.SETTLED, task.getStatus(), "task 须置 SETTLED");
        assertEquals(TaskStatus.SETTLED, assignment.getStatus(), "assignment 须同步置 SETTLED（本次修复点）");
        assertNotNull(task.getSettledAt(), "task.settledAt 须回填");

        // 用 ArgumentCaptor 抓到真正提交给仓储的实体，比断言 save 次数可靠
        ArgumentCaptor<TaskAssignment> settledCaptor = ArgumentCaptor.forClass(TaskAssignment.class);
        verify(taskAssignmentRepository, atLeastOnce()).save(settledCaptor.capture());
        List<TaskAssignment> savedAssignments = settledCaptor.getAllValues();
        TaskAssignment lastSavedAssignment = savedAssignments.get(savedAssignments.size() - 1);
        assertEquals(ASSIGNMENT_ID, lastSavedAssignment.getId());
        assertEquals(TaskStatus.SETTLED, lastSavedAssignment.getStatus(),
                "最后一次落库的 assignment 必须是 SETTLED");

        // ---------- 5. assetEarnings：修复后应返回 1 条收益 ----------
        String expectedBizRef = "TASK-" + TASK_ID + "-" + ASSIGNMENT_ID;
        when(taskAssignmentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(assignment));
        when(accountService.findEntriesByBizRef(expectedBizRef)).thenReturn(List.of(
                new LedgerViews.EntryView(901L, null, 20L, "C", REWARD, "TASK_SETTLEMENT",
                        expectedBizRef, "任务报酬收入 " + expectedBizRef, Instant.now())));

        List<TaskViews.TaskEarningView> earnings = settlementService.assetEarnings(ASSET_ID, PROVIDER_ID);

        assertEquals(1, earnings.size(), "已结算接单须能反查到 1 条收益（修复前恒为 0）");
        TaskViews.TaskEarningView earning = earnings.get(0);
        assertEquals(TASK_ID, earning.taskId());
        assertEquals(ASSIGNMENT_ID, earning.assignmentId());
        assertEquals(ASSET_ID, earning.assetId());
        assertEquals(expectedBizRef, earning.bizRef());
        assertEquals(0, REWARD.compareTo(earning.amount()));

        // ---------- 6. 过账只发生一次，且 bizRef 与对账反查用的完全一致（防拼串漂移）----------
        ArgumentCaptor<String> bizRefCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerRequests.Entry>> entriesCaptor =
                (ArgumentCaptor<List<LedgerRequests.Entry>>) (Object) ArgumentCaptor.forClass(List.class);
        verify(ledgerService, times(1))
                .postEntries(any(BizType.class), bizRefCaptor.capture(), entriesCaptor.capture());
        assertEquals(expectedBizRef, bizRefCaptor.getValue(), "过账 bizRef 须与对账反查 bizRef 一致");
        assertEquals(2, entriesCaptor.getValue().size());
    }

    /**
     * 断言 {@link TaskService#complete(Long, Long)} 确实调用了结算（verify），并用 spy 保留真实结算行为。
     * 防止后续重构把 settle 调用摘掉而链路测试仍「看起来绿」。
     */
    @Test
    void complete_delegatesToSettleWithTaskAndAssignment() {
        TaskSettlementService spySettlement = spy(settlementService);
        TaskService wired = new TaskService(taskRepository, taskAssignmentRepository, taskLogisticsRepository,
                taskRideRepository, taskAdRepository, assetRepository, spySettlement, droneMissionRepository);

        Task task = task(TaskStatus.IN_PROGRESS);
        TaskAssignment assignment = assignment(TaskStatus.IN_PROGRESS);

        when(taskAssignmentRepository.findByTaskIdAndProviderId(TASK_ID, PROVIDER_ID))
                .thenReturn(Optional.of(assignment));
        when(taskAssignmentRepository.save(any(TaskAssignment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(accountService.getOrCreateUserAccount(PUBLISHER_ID))
                .thenReturn(Account.builder().id(10L).userId(PUBLISHER_ID).build());
        when(accountService.getOrCreateUserAccount(PROVIDER_ID))
                .thenReturn(Account.builder().id(20L).userId(PROVIDER_ID).build());

        wired.complete(TASK_ID, PROVIDER_ID);

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        ArgumentCaptor<TaskAssignment> assignmentCaptor = ArgumentCaptor.forClass(TaskAssignment.class);
        verify(spySettlement, times(1)).settle(taskCaptor.capture(), assignmentCaptor.capture());

        assertEquals(TASK_ID, taskCaptor.getValue().getId());
        assertEquals(ASSIGNMENT_ID, assignmentCaptor.getValue().getId());
        assertEquals(TaskStatus.SETTLED, taskCaptor.getValue().getStatus());
        assertEquals(TaskStatus.SETTLED, assignmentCaptor.getValue().getStatus());
        verify(ledgerService, times(1)).postEntries(any(BizType.class), anyString(), anyList());
    }

    /** 直接对真实 TaskSettlementService 调 settle()：task 与 assignment 都置 SETTLED 且都落库。 */
    @Test
    void settle_marksBothTaskAndAssignmentSettledAndPersistsBoth() {
        Task task = task(TaskStatus.COMPLETED);
        TaskAssignment assignment = assignment(TaskStatus.COMPLETED);

        when(accountService.getOrCreateUserAccount(PUBLISHER_ID))
                .thenReturn(Account.builder().id(10L).userId(PUBLISHER_ID).build());
        when(accountService.getOrCreateUserAccount(PROVIDER_ID))
                .thenReturn(Account.builder().id(20L).userId(PROVIDER_ID).build());

        settlementService.settle(task, assignment);

        assertEquals(TaskStatus.SETTLED, task.getStatus());
        assertEquals(TaskStatus.SETTLED, assignment.getStatus());
        assertNotNull(task.getSettledAt());

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        ArgumentCaptor<TaskAssignment> assignmentCaptor = ArgumentCaptor.forClass(TaskAssignment.class);
        verify(taskRepository, times(1)).save(taskCaptor.capture());
        verify(taskAssignmentRepository, times(1)).save(assignmentCaptor.capture());
        assertEquals(TaskStatus.SETTLED, taskCaptor.getValue().getStatus());
        assertEquals(TaskStatus.SETTLED, assignmentCaptor.getValue().getStatus());
        assertEquals(ASSIGNMENT_ID, assignmentCaptor.getValue().getId());
    }

    /**
     * 幂等：task 已 SETTLED 时直接返回，绝不二次过账（资金重复出账守护）。
     * task 与 assignment 在同一事务内提交，不存在「task 已结算而 assignment 未结算」的中间态。
     */
    @Test
    void settle_whenTaskAlreadySettled_skipsPosting() {
        Task task = task(TaskStatus.SETTLED);
        TaskAssignment assignment = assignment(TaskStatus.SETTLED);

        settlementService.settle(task, assignment);

        verifyNoInteractions(ledgerService);
        verify(taskRepository, never()).save(any(Task.class));
        verify(taskAssignmentRepository, never()).save(any(TaskAssignment.class));
    }

    /**
     * 过账失败不得置已结算：task / assignment 保持 COMPLETED 且不落库。
     * 守护「未过账却显示已结算」这类最危险的资金状态错配。
     */
    @Test
    void settle_whenPostingFails_keepsCompletedAndDoesNotPersist() {
        Task task = task(TaskStatus.COMPLETED);
        TaskAssignment assignment = assignment(TaskStatus.COMPLETED);

        when(accountService.getOrCreateUserAccount(PUBLISHER_ID))
                .thenReturn(Account.builder().id(10L).userId(PUBLISHER_ID).build());
        when(accountService.getOrCreateUserAccount(PROVIDER_ID))
                .thenReturn(Account.builder().id(20L).userId(PROVIDER_ID).build());
        doThrow(BizException.of(42251, "error.ledger.balance.insufficient"))
                .when(ledgerService).postEntries(any(BizType.class), anyString(), anyList());

        assertThrows(BizException.class, () -> settlementService.settle(task, assignment));

        assertEquals(TaskStatus.COMPLETED, task.getStatus(), "过账失败不得置 SETTLED");
        assertEquals(TaskStatus.COMPLETED, assignment.getStatus(), "过账失败不得置 SETTLED");
        verify(taskRepository, never()).save(any(Task.class));
        verify(taskAssignmentRepository, never()).save(any(TaskAssignment.class));
    }

    /** DRONE_OP 发布请求（missionType=SPRAY，资产 55L / 飞手 88L）。 */
    private static TaskRequests.Publish droneReq() {
        return new TaskRequests.Publish(
                TaskType.DRONE_OP,
                "Field spraying",
                "north parcel",
                REWARD,
                "USD",
                AssetCapability.DRONE_OP,
                new BigDecimal("31.23040000"), new BigDecimal("121.47370000"), 2000,
                null, null, null, null,                       // pickup/dropoff/cargo/weight
                null, null, null, null, null, null,           // origin/dest/rideType/estDistance/estDuration/fareModel
                null, null, null, null,                       // advertiser/media/display/screen
                DroneMissionType.SPRAY.name(), "pesticide",   // missionType/payloadDesc
                new BigDecimal("12.50"), 3, 45,               // areaHa/trips/flightMinutes
                88L, ASSET_ID, null);                         // pilotId/assetId/executedAt
    }

    private static Optional<Asset> asset() {
        return Optional.of(Asset.builder().id(ASSET_ID).userId(PROVIDER_ID).capabilities("DRONE_OP").build());
    }

    private static Task task(TaskStatus status) {
        return Task.builder()
                .id(TASK_ID)
                .publisherId(PUBLISHER_ID)
                .taskType(TaskType.DRONE_OP)
                .title("Field spraying")
                .rewardAmount(REWARD)
                .currency("USD")
                .capabilityRequired(AssetCapability.DRONE_OP)
                .status(status)
                .build();
    }

    private static TaskAssignment assignment(TaskStatus status) {
        return TaskAssignment.builder()
                .id(ASSIGNMENT_ID)
                .taskId(TASK_ID)
                .providerId(PROVIDER_ID)
                .assetId(ASSET_ID)
                .status(status)
                .progressPct(100)
                .build();
    }
}
