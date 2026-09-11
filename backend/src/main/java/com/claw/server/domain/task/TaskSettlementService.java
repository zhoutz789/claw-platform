package com.claw.server.domain.task;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.dto.TaskViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.TaskStatus;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 任务结算服务（P0：LOGISTICS 闭环的账本写入）。
 *
 * <p>复用既有 {@link LedgerService#postEntries} 复式记账引擎，绝不复刻第二套账本/账户体系。
 * 平台佣金费率取自 {@code system_config.TASK_HALL_PLATFORM_RATE}，缺省/非法按 0 处理，
 * 因此默认行为与「发布方全额支付给接单方」完全一致。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskSettlementService {

    /** 平台佣金费率配置键；缺省/非法按 0 处理（发布方全额支付给接单方）。 */
    private static final String PLATFORM_RATE_KEY = "TASK_HALL_PLATFORM_RATE";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final AssetRepository assetRepository;
    private final SystemConfigRepository systemConfigRepository;

    /**
     * 结算单笔任务：publisher 出账 reward，provider 入账 (reward - commission)，
     * 平台入账 commission（默认费率为 0，即不产生平台分录）。
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

        // reward_amount 为 NUMERIC(18,4)，而 account_entries.amount / accounts.balance 为 NUMERIC(16,2)。
        // 先把报酬按 2 位四舍五入（HALF_UP），再按【减法】拆出接单方金额，
        // 保证 publisher D == provider C + platform C 在 2 位精度下严格平衡：
        //   provider = round(reward) - commission，而不是 reward * (1 - rate)（后者会因两次舍入而不平衡）。
        BigDecimal rewardRounded = task.getRewardAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal rate = platformRate();
        BigDecimal commission = rewardRounded.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal providerAmount = rewardRounded.subtract(commission);

        List<LedgerRequests.Entry> entries = new ArrayList<>();
        entries.add(new LedgerRequests.Entry(publisherAcct.getId(), LedgerRequests.Direction.D, rewardRounded,
                "任务报酬支付 " + bizRef));
        entries.add(new LedgerRequests.Entry(providerAcct.getId(), LedgerRequests.Direction.C, providerAmount,
                "任务报酬收入 " + bizRef));
        // 仅在佣金 > 0 时追加平台分录：
        //   1) 费率 0 时保持与历史完全一致的 2 条分录（零费率路径不额外解析平台户）；
        //   2) 绝不写入金额为 0 的分录 —— account_entries.amount 有 `amount > 0` 的 CHECK 约束，
        //      而 ledger 的内存层只校验 sum(D)==sum(C)，抓不到 0 金额分录。
        if (commission.signum() > 0) {
            Account platformAcct = accountService.getOrCreatePlatformAccount(AccountType.MASTER);
            entries.add(new LedgerRequests.Entry(platformAcct.getId(), LedgerRequests.Direction.C, commission,
                    "平台佣金 " + bizRef));
        }

        // 借贷必然平衡（publisher D == provider C + platform C），42250 不会触发；
        // 余额不足 42251 / 重复 40950 直接上抛。
        ledgerService.postEntries(BizType.TASK_SETTLEMENT, bizRef, entries);

        task.setStatus(TaskStatus.SETTLED);
        task.setSettledAt(Instant.now());
        taskRepository.save(task);

        // 过账成功后才置已结算：避免「未过账却显示已结算」。
        // assignment 必须与 task 一同置 SETTLED，否则 assetEarnings() 对账口径（status == SETTLED）永远命中不了。
        assignment.setStatus(TaskStatus.SETTLED);
        assignment.setUpdatedAt(Instant.now());
        taskAssignmentRepository.save(assignment);

        log.info("任务结算完成 bizRef={} task={} taskStatus={} assignment={} assignmentStatus={} amount={} commission={} rate={}",
                bizRef, task.getId(), task.getStatus(), assignment.getId(), assignment.getStatus(),
                rewardRounded, commission, rate);
    }

    /**
     * 资产收益对账：仅资产归属人可见。反查该资产下已结算接单、
     * 且入账到【接单方本人主账户】的账本 C 方向分录。
     *
     * <p>必须按账户过滤：平台佣金同为 C 分录，但记在平台内部户（{@code user_id IS NULL}）上，
     * 若不区分账户，平台佣金会被误报为接单方的资产收益（分录数虚增、金额虚高）。
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
            // 只读路径：绝不调用 getOrCreate*（开户有副作用）；账户不存在则该接单不计收益。
            Optional<Account> providerAcct = accountService.findUserAccount(assignment.getProviderId());
            if (providerAcct.isEmpty()) {
                continue;
            }
            Long providerAccountId = providerAcct.get().getId();
            for (LedgerViews.EntryView e : accountService.findEntriesByBizRef(bizRef)) {
                if ("C".equals(e.direction()) && Objects.equals(e.accountId(), providerAccountId)) {
                    result.add(new TaskViews.TaskEarningView(
                            taskId, assignment.getId(), assetId, e.amount(), bizRef, e.memo(),
                            e.createdAt()));
                }
            }
        }
        return result;
    }

    /**
     * 平台佣金费率（占报酬比例），取自 {@code system_config.TASK_HALL_PLATFORM_RATE}，合法区间 [0, 1]。
     *
     * <p>缺失 / 空白 / 非数字 / 负数 / 大于 1 一律按 0 处理并 {@code log.warn}
     * （费率 > 1 会让接单方所得变成负数，直接破坏资金正确性）。
     *
     * @return 合法费率；非法时返回 {@link BigDecimal#ZERO}
     */
    private BigDecimal platformRate() {
        String raw = systemConfigRepository.findByConfigKeyAndDeletedFalse(PLATFORM_RATE_KEY)
                .map(SystemConfig::getConfigValue)
                .orElse(null);
        if (raw == null || raw.isBlank()) {
            log.warn("system_config.{} 缺失或为空，按 0 处理（发布方全额支付给接单方）", PLATFORM_RATE_KEY);
            return ZERO;
        }
        try {
            BigDecimal rate = new BigDecimal(raw.trim());
            if (rate.signum() < 0 || rate.compareTo(ONE) > 0) {
                log.warn("system_config.{} 超出合法区间 [0,1]，按 0 处理 value={}", PLATFORM_RATE_KEY, raw);
                return ZERO;
            }
            return rate;
        } catch (NumberFormatException e) {
            log.warn("system_config.{} 非数字，按 0 处理 value={}", PLATFORM_RATE_KEY, raw);
            return ZERO;
        }
    }
}
