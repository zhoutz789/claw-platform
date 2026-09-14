package com.claw.server.domain.report;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.AssetType;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 无人机收益报告聚合服务（切片 4a / T14，只读聚合，无新迁移）。
 *
 * <p><b>口径铁律</b>：金额全部取自已入账的真实凭证，与既有结算/清分 100% 一致，
 * 不新增资金池、不重建结算逻辑、不写任何账：
 * <ul>
 *   <li>任务报酬 —— 与 {@code TaskSettlementService#assetEarnings} 完全同口径：
 *       仅统计 {@code assignment.status == SETTLED} 且 bizRef 为
 *       {@code TASK-<taskId>-<assignmentId>} 的账本 C 方向分录，
 *       并按【接单方本人主账户】过滤（平台佣金记在平台内部户，绝不误报为资产收益）；
 *       时间窗锚点为 {@code tasks.settled_at}（结算完成时刻）。</li>
 *   <li>容量回佣 —— {@code capacity_plans(capacity_type='PARALLEL')} 挂到该无人机的计划，
 *       取 {@code capacity_rebate_settlements.amount}（{@code status=SETTLED} 行），
 *       时间窗锚点为回佣明细 {@code created_at}。</li>
 * </ul>
 *
 * <p>返回结构镜像 {@link VehicleEarningsReportService}（明细行 + grossTotal +
 * capacityUserShare + platformShare + 币种 USD）；总览 {@link #overview} 额外提供
 * 分资产小结。时间参数由调用方（控制器）保证必传，本服务兜底校验 from ≤ to。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DroneEarningsReportService {

    /** 收益来源：任务报酬（TASK_SETTLEMENT 账本分录）。 */
    public static final String SOURCE_TASK = "TASK_SETTLEMENT";

    /** 收益来源：容量回佣（capacity_rebate_settlements）。 */
    public static final String SOURCE_REBATE = "CAPACITY_REBATE";

    /** 后端结算默认币种（与 TaskSettlementService / LedgerService 一致）。 */
    private static final String DEFAULT_CURRENCY = "USD";

    private static final int SCALE = 2;

    /** 任务收益 bizRef 前缀（与 TaskSettlementService.settle 的 bizRef 规则一致）。 */
    private static final String TASK_BIZ_REF_PREFIX = "TASK-";

    /** 无人机容量产能类型（与 V142 种子及 DroneOpsController 容量端点一致）。 */
    private static final com.claw.server.common.enums.CapacityType PARALLEL =
            com.claw.server.common.enums.CapacityType.PARALLEL;

    private final AssetRepository assetRepository;
    private final DroneMissionRepository droneMissionRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final TaskRepository taskRepository;
    private final AccountService accountService;
    private final CapacityPlanRepository capacityPlanRepository;
    private final CapacityRebateSettlementRepository rebateSettlementRepository;

    /**
     * 单无人机资产收益报表。
     *
     * @param assetId 无人机资产 ID（必须是 {@code AssetType.DRONE} 资产，否则 404）
     * @param from    统计开始时间（含，必传）
     * @param to      统计结束时间（含，必传）
     * @return 收益明细 + 合计（只读，不写账）
     */
    @Transactional(readOnly = true)
    public DroneEarningsReport generate(Long assetId, Instant from, Instant to) {
        validateWindow(from, to);
        requireDroneAsset(assetId);

        Map<String, DroneEarningsReport.EarningsLine> lines = new LinkedHashMap<>();
        Aggregate taskAgg = collectTaskEarnings(assetId, from, to, lines);
        Aggregate rebateAgg = collectCapacityRebate(assetId, from, to, lines);

        BigDecimal gross = scale(taskAgg.amount.add(rebateAgg.amount));
        return DroneEarningsReport.builder()
                .assetId(assetId)
                .from(from)
                .to(to)
                .lines(new ArrayList<>(lines.values()))
                .grossTotal(gross)
                .capacityUserShare(scale(rebateAgg.amount))
                .platformShare(scale(taskAgg.amount))
                .currency(DEFAULT_CURRENCY)
                .build();
    }

    /**
     * 全网无人机收益总览（/earnings/overview）：全部 DRONE 资产在时间窗内的收益聚合。
     *
     * <p>逐资产复用与 {@link #generate} 完全相同的采集函数（同一口径，绝不二次实现），
     * 再做全网汇总与分资产小结。
     */
    @Transactional(readOnly = true)
    public DroneEarningsReport.Overview overview(Instant from, Instant to) {
        validateWindow(from, to);

        List<Asset> drones = assetRepository.findByAssetTypeAndDeletedFalse(AssetType.DRONE);

        Map<String, DroneEarningsReport.EarningsLine> lines = new LinkedHashMap<>();
        List<DroneEarningsReport.AssetSummary> byAsset = new ArrayList<>();
        Aggregate taskAgg = new Aggregate();
        Aggregate rebateAgg = new Aggregate();

        for (Asset drone : drones) {
            Map<String, DroneEarningsReport.EarningsLine> assetLines = new LinkedHashMap<>();
            Aggregate assetTask = collectTaskEarnings(drone.getId(), from, to, assetLines);
            Aggregate assetRebate = collectCapacityRebate(drone.getId(), from, to, assetLines);

            // 合入全网明细行（同名行累加）
            for (DroneEarningsReport.EarningsLine line : assetLines.values()) {
                mergeLine(lines, line);
            }
            taskAgg.add(assetTask);
            rebateAgg.add(assetRebate);

            if (assetTask.count > 0 || assetRebate.count > 0) {
                byAsset.add(DroneEarningsReport.AssetSummary.builder()
                        .assetId(drone.getId())
                        .taskEarningsTotal(scale(assetTask.amount))
                        .rebateTotal(scale(assetRebate.amount))
                        .grossTotal(scale(assetTask.amount.add(assetRebate.amount)))
                        .build());
            }
        }

        return DroneEarningsReport.Overview.builder()
                .from(from)
                .to(to)
                .assetCount((long) drones.size())
                .lines(new ArrayList<>(lines.values()))
                .byAsset(byAsset)
                .taskEarningsTotal(scale(taskAgg.amount))
                .rebateTotal(scale(rebateAgg.amount))
                .grossTotal(scale(taskAgg.amount.add(rebateAgg.amount)))
                .currency(DEFAULT_CURRENCY)
                .build();
    }

    // ------------------------------------------------------------------ 口径采集

    /**
     * 采集任务报酬（source=TASK_SETTLEMENT）。
     *
     * <p>口径与 {@code TaskSettlementService#assetEarnings} 一致：
     * assignment SETTLED + 任务为 DRONE_OP + 结算时刻落窗 + C 方向分录记在接单方主账户。
     * 只读路径绝不调用 {@code getOrCreate*}（开户有副作用），账户不存在则该笔不计收益。
     *
     * @return 该资产的聚合容器（笔数 + 金额）
     */
    private Aggregate collectTaskEarnings(Long assetId, Instant from, Instant to,
                                          Map<String, DroneEarningsReport.EarningsLine> lines) {
        Aggregate agg = new Aggregate();

        // 作业类型映射（missionId → missionType），供明细行 subtype 使用。
        Map<Long, String> missionTypes = new LinkedHashMap<>();
        for (DroneMission mission : droneMissionRepository.findByAssetId(assetId)) {
            missionTypes.put(mission.getId(),
                    mission.getMissionType() == null ? "UNKNOWN" : mission.getMissionType().name());
        }

        for (TaskAssignment assignment : taskAssignmentRepository.findByAssetId(assetId)) {
            if (assignment.getStatus() != TaskStatus.SETTLED) {
                continue;
            }
            Task task = taskRepository.findById(assignment.getTaskId()).orElse(null);
            if (task == null || task.getTaskType() != TaskType.DRONE_OP) {
                continue; // 无人机报表只统计 DRONE_OP 任务（其他类型任务走车辆/物流等报表口径）
            }
            if (task.getSettledAt() == null
                    || task.getSettledAt().isBefore(from)
                    || task.getSettledAt().isAfter(to)) {
                continue; // 时间窗锚点：tasks.settled_at（结算完成时刻）
            }

            // 只读查接单方主账户；不存在则该笔不计（与 assetEarnings 口径一致）。
            Optional<Account> providerAcct = accountService.findUserAccount(assignment.getProviderId());
            if (providerAcct.isEmpty()) {
                continue;
            }
            Long providerAccountId = providerAcct.get().getId();

            String bizRef = TASK_BIZ_REF_PREFIX + task.getId() + "-" + assignment.getId();
            for (LedgerViews.EntryView entry : accountService.findEntriesByBizRef(bizRef)) {
                if (!"C".equals(entry.direction())
                        || !Objects.equals(entry.accountId(), providerAccountId)) {
                    continue; // 平台佣金同为 C 但记在平台内部户，必须按账户过滤掉
                }
                String subtype = task.getDroneMissionId() != null
                        ? missionTypes.getOrDefault(task.getDroneMissionId(), "UNKNOWN")
                        : "UNKNOWN";
                agg.add(entry.amount());
                mergeLine(lines, DroneEarningsReport.EarningsLine.builder()
                        .source(SOURCE_TASK)
                        .subtype(subtype)
                        .count(1L)
                        .amount(scale(entry.amount()))
                        .build());
            }
        }
        return agg;
    }

    /**
     * 采集容量回佣（source=CAPACITY_REBATE）。
     *
     * <p>口径：挂在该无人机资产上的 {@code capacity_plans(capacity_type='PARALLEL')} 计划，
     * 取其 {@code capacity_rebate_settlements} 中 {@code status=SETTLED} 行的 {@code amount}，
     * 时间窗锚点为回佣明细 {@code created_at}（V71 回佣生成即结算）。
     *
     * @return 该资产的聚合容器（笔数 + 金额）
     */
    private Aggregate collectCapacityRebate(Long assetId, Instant from, Instant to,
                                            Map<String, DroneEarningsReport.EarningsLine> lines) {
        Aggregate agg = new Aggregate();
        for (CapacityPlan plan : capacityPlanRepository.findByAssetIdAndDeletedFalse(assetId)) {
            if (plan.getCapacityType() != PARALLEL) {
                continue; // 无人机产能设计上属并行额度，与车辆 SERIAL 产能区分
            }
            for (CapacityRebateSettlement settlement
                    : rebateSettlementRepository.findByPlanIdAndDeletedFalse(plan.getId())) {
                if (settlement.getStatus() != com.claw.server.common.enums.RebateStatus.SETTLED) {
                    continue;
                }
                Instant createdAt = settlement.getCreatedAt();
                if (createdAt == null || createdAt.isBefore(from) || createdAt.isAfter(to)) {
                    continue;
                }
                agg.add(settlement.getAmount());
                mergeLine(lines, DroneEarningsReport.EarningsLine.builder()
                        .source(SOURCE_REBATE)
                        .subtype("plan-" + plan.getId())
                        .count(1L)
                        .amount(scale(settlement.getAmount()))
                        .build());
            }
        }
        return agg;
    }

    // ------------------------------------------------------------------ 工具

    /** 校验时间窗：from/to 必传且 from ≤ to，否则 400。 */
    private static void validateWindow(Instant from, Instant to) {
        if (from == null || to == null) {
            throw BizException.invalidParam("error.drone.earnings.window.invalid");
        }
        if (from.isAfter(to)) {
            throw BizException.invalidParam("error.drone.earnings.window.invalid");
        }
    }

    /** 要求资产存在且为无人机（复用既有 error.drone.not.found，404）。 */
    private void requireDroneAsset(Long assetId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> BizException.notFound("error.drone.not.found:" + assetId));
        if (asset.getAssetType() != AssetType.DRONE) {
            // 非无人机资产没有「无人机收益」概念，按未找到无人机资产处理。
            throw BizException.notFound("error.drone.not.found:" + assetId);
        }
    }

    /** 合并明细行：同 source+subtype 行累加笔数与金额（LinkedHashMap 保序）。 */
    private static void mergeLine(Map<String, DroneEarningsReport.EarningsLine> lines,
                                  DroneEarningsReport.EarningsLine line) {
        String key = line.getSource() + ":" + line.getSubtype();
        DroneEarningsReport.EarningsLine existing = lines.get(key);
        if (existing == null) {
            lines.put(key, line);
            return;
        }
        lines.put(key, DroneEarningsReport.EarningsLine.builder()
                .source(existing.getSource())
                .subtype(existing.getSubtype())
                .count(existing.getCount() + line.getCount())
                .amount(scale(existing.getAmount().add(line.getAmount())))
                .build());
    }

    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 聚合容器（笔数 + 金额），供任务/回佣两路采集复用。 */
    private static final class Aggregate {
        private long count;
        private BigDecimal amount = BigDecimal.ZERO;

        private void add(BigDecimal amt) {
            this.count += 1;
            this.amount = this.amount.add(amt == null ? BigDecimal.ZERO : amt);
        }

        private void add(Aggregate other) {
            this.count += other.count;
            this.amount = this.amount.add(other.amount);
        }
    }
}
