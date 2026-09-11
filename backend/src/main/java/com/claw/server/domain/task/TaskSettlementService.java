package com.claw.server.domain.task;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.dto.TaskViews;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.TaskStatus;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 任务结算服务（P0：LOGISTICS 闭环的账本写入）。
 *
 * <p>复用既有 {@link LedgerService#postEntries} 复式记账引擎，绝不复刻第二套账本/账户体系。
 * Phase 1 平台佣金 = 0，预留 system_config 费率钩子。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskSettlementService {

    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final AssetRepository assetRepository;

    /**
     * 结算单笔任务：publisher 出账 reward，provider 入账 reward（平台佣金 P1 预留）。
     *
     * <p>过账成功后，task 与 assignment 在同一事务内双双置 SETTLED 并落库：
     * {@link #assetEarnings(Long, Long)} 以 {@code assignment.status == SETTLED} 为对账口径，
     * 若只置 task 不置 assignment，资金虽已正确过账，资产收益查询却恒返回空（账对了、对账查不到）。
     *
     * @param task       已完成任务（结算后置 SETTLED）
     * @param assignment 接单记录（bizRef 组分的一部分，结算后同步置 SETTLED）
     */
    @Transactional
    public void settle(Task task, TaskAssignment assignment) {
        // 幂等护栏：已结算直接返回（与 ledger 幂等键 bizType+bizRef 双保险）。
        if (task.getStatus() == TaskStatus.SETTLED) {
            return;
        }
        String bizRef = "TASK-" + task.getId() + "-" + assignment.getId();

        Account publisherAcct = accountService.getOrCreateUserAccount(task.getPublisherId());
        Account providerAcct = accountService.getOrCreateUserAccount(assignment.getProviderId());
        BigDecimal reward = task.getRewardAmount();

        // Phase 1：平台佣金为 0。
        // TODO(P2): 后续从 system_config 读取 platformRate，按比例拆出佣金分录：
        //   publisher D reward / platform-offset C commission / provider C (reward - commission)。
        List<LedgerRequests.Entry> entries = List.of(
                new LedgerRequests.Entry(publisherAcct.getId(), LedgerRequests.Direction.D, reward,
                        "任务报酬支付 " + bizRef),
                new LedgerRequests.Entry(providerAcct.getId(), LedgerRequests.Direction.C, reward,
                        "任务报酬收入 " + bizRef));

        // 借贷必然平衡（reward == reward），42250 不会触发；余额不足 42251 / 重复 40950 直接上抛。
        ledgerService.postEntries(BizType.TASK_SETTLEMENT, bizRef, entries);

        task.setStatus(TaskStatus.SETTLED);
        task.setSettledAt(Instant.now());
        taskRepository.save(task);

        // 过账成功后才置已结算：避免「未过账却显示已结算」。
        // assignment 必须与 task 一同置 SETTLED，否则 assetEarnings() 对账口径（status == SETTLED）永远命中不了。
        assignment.setStatus(TaskStatus.SETTLED);
        assignment.setUpdatedAt(Instant.now());
        taskAssignmentRepository.save(assignment);

        log.info("任务结算完成 bizRef={} task={} taskStatus={} assignment={} assignmentStatus={} amount={}",
                bizRef, task.getId(), task.getStatus(), assignment.getId(), assignment.getStatus(), reward);
    }

    /**
     * 资产收益对账：仅资产归属人可见。反查该资产下已结算接单的账本 C 方向分录。
     */
    @Transactional(readOnly = true)
    public List<TaskViews.TaskEarningView> assetEarnings(Long assetId, Long viewerId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> BizException.notFound("error.asset.not.found"));
        if (!Objects.equals(asset.getUserId(), viewerId) && !Objects.equals(asset.getOwnerId(), viewerId)) {
            throw BizException.forbidden("error.task.earnings.forbidden");
        }

        List<TaskViews.TaskEarningView> result = new ArrayList<>();
        for (TaskAssignment assignment : taskAssignmentRepository.findByAssetId(assetId)) {
            if (assignment.getStatus() != TaskStatus.SETTLED) {
                continue;
            }
            Long taskId = assignment.getTaskId();
            String bizRef = "TASK-" + taskId + "-" + assignment.getId();
            for (LedgerViews.EntryView e : accountService.findEntriesByBizRef(bizRef)) {
                if ("C".equals(e.direction())) {
                    result.add(new TaskViews.TaskEarningView(
                            taskId, assignment.getId(), assetId, e.amount(), bizRef, e.memo(),
                            e.createdAt()));
                }
            }
        }
        return result;
    }
}
