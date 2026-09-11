package com.claw.server.domain.task;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.TaskRequests;
import com.claw.server.common.dto.TaskViews;
import com.claw.server.common.enums.AssetCapability;
import com.claw.server.common.enums.DroneMissionType;
import com.claw.server.common.enums.TaskStatus;
import com.claw.server.common.enums.TaskType;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.payload.DroneMission;
import com.claw.server.domain.payload.DroneMissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务大厅领域服务（P0：LOGISTICS 完整闭环；P2：HAIL_RIDE / TAXI / AD 扩展打通）。
 *
 * <p>发布 → 接单（绑资产）→ 进度 → 完成 → 结算（走 {@link TaskSettlementService} 复用 ledger 双记账）。
 * LOGISTICS 落 task_logistics；HAIL_RIDE / TAXI 落 task_ride；AD 落 task_ad。结算对全类型通用，P2 不改动。
 * 仅依赖 common 层 DTO 与枚举，不触碰权限/RBAC 体系。
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final TaskLogisticsRepository taskLogisticsRepository;
    private final TaskRideRepository taskRideRepository;
    private final TaskAdRepository taskAdRepository;
    private final AssetRepository assetRepository;
    private final TaskSettlementService taskSettlementService;
    private final DroneMissionRepository droneMissionRepository;

    /** 任务/接单已处于终态（SETTLED / CANCELLED），拒绝任何后续流转（HTTP 409）。 */
    private static final int TASK_ALREADY_TERMINAL = 40902;

    /** taskType → 预期 capability_required 的强制映射（发布校验用）。 */
    private static final Map<TaskType, AssetCapability> CAPABILITY_FOR_TYPE = Map.of(
            TaskType.LOGISTICS, AssetCapability.LOGISTICS,
            TaskType.HAIL_RIDE, AssetCapability.RIDE_HAIL,
            TaskType.TAXI, AssetCapability.TAXI,
            TaskType.AD, AssetCapability.AD_DISPLAY,
            TaskType.DRONE_OP, AssetCapability.DRONE_OP);

    @Transactional
    public TaskViews.TaskView publish(TaskRequests.Publish req, Long publisherId) {
        if (req.rewardAmount() == null || req.rewardAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.invalidParam("error.task.reward.positive");
        }
        AssetCapability expected = CAPABILITY_FOR_TYPE.get(req.taskType());
        if (expected == null || expected != req.capabilityRequired()) {
            throw BizException.invalidParam("error.task.capability.mismatch");
        }
        String currency = (req.currency() == null || req.currency().isBlank()) ? "USD" : req.currency();

        Task task = Task.builder()
                .publisherId(publisherId)
                .taskType(req.taskType())
                .title(req.title())
                .description(req.description())
                .rewardAmount(req.rewardAmount())
                .currency(currency)
                .capabilityRequired(req.capabilityRequired())
                .geoLat(req.geoLat())
                .geoLng(req.geoLng())
                .serviceRadiusM(req.serviceRadiusM())
                .createdAt(Instant.now())
                .build();
        task = taskRepository.save(task);

        if (req.taskType() == TaskType.LOGISTICS) {
            TaskLogistics logistics = TaskLogistics.builder()
                    .taskId(task.getId())
                    .pickupAddr(req.pickupAddr())
                    .dropoffAddr(req.dropoffAddr())
                    .cargoType(req.cargoType())
                    .weightKg(req.weightKg())
                    .build();
            taskLogisticsRepository.save(logistics);
        } else if (req.taskType() == TaskType.HAIL_RIDE || req.taskType() == TaskType.TAXI) {
            String expectedRideType = (req.taskType() == TaskType.HAIL_RIDE) ? "HAIL" : "TAXI";
            if (req.rideType() == null || !expectedRideType.equals(req.rideType())) {
                throw BizException.invalidParam("error.task.ride.type");
            }
            TaskRide ride = TaskRide.builder()
                    .taskId(task.getId())
                    .originAddr(req.originAddr())
                    .destAddr(req.destAddr())
                    .rideType(req.rideType())
                    .estDistanceKm(req.estDistanceKm())
                    .estDurationMin(req.estDurationMin())
                    .fareModel(req.fareModel())
                    .build();
            taskRideRepository.save(ride);
        } else if (req.taskType() == TaskType.AD) {
            if (req.screenType() == null || (!"BODY".equals(req.screenType()) && !"SCREEN".equals(req.screenType()))) {
                throw BizException.invalidParam("error.task.ad.screen.type");
            }
            TaskAd ad = TaskAd.builder()
                    .taskId(task.getId())
                    .advertiser(req.advertiser())
                    .mediaUrl(req.mediaUrl())
                    .displayDuration(req.displayDuration())
                    .screenType(req.screenType())
                    .build();
            taskAdRepository.save(ad);
        } else if (req.taskType() == TaskType.DRONE_OP) {
            DroneMission saved = droneMissionRepository.save(buildDroneMission(req));
            task.setDroneMissionId(saved.getId());
            task = taskRepository.save(task);
        }
        return toViewWithExtension(task);
    }

    @Transactional(readOnly = true)
    public TaskViews.TaskView detail(Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> BizException.notFound("error.task.not.found"));
        return toViewWithExtension(task);
    }

    @Transactional(readOnly = true)
    public List<TaskViews.TaskView> listForPublisher(Long publisherId) {
        return taskRepository.findByPublisherIdAndDeletedFalse(publisherId).stream()
                .map(this::toViewWithExtension)
                .toList();
    }

    /**
     * provider 可接任务：汇总其名下所有资产能力 → 反查 OPEN 任务 → 去重 → 排除已接。
     */
    @Transactional(readOnly = true)
    public List<TaskViews.TaskView> listAvailableForProvider(Long providerId) {
        return listAvailableForProvider(providerId, null, null);
    }

    /**
     * provider 可接任务（带地理过滤）：汇总其名下所有资产能力 → 反查 OPEN 任务 → 去重 → 排除已接。
     *
     * <p>当 lat / lng 均提供时，仅保留「任务无坐标」或「未设服务半径」或「距离 ≤ 服务半径」的候选，
     * 避免 provider 看到自身服务范围外无法履约的任务。坐标缺省时行为与 {@link #listAvailableForProvider(Long)} 一致。
     */
    @Transactional(readOnly = true)
    public List<TaskViews.TaskView> listAvailableForProvider(Long providerId, BigDecimal lat, BigDecimal lng) {
        List<Asset> assets = assetRepository.findByUserIdAndDeletedFalse(providerId);
        Set<AssetCapability> caps = assets.stream()
                .flatMap(a -> parseCapabilities(a.getCapabilities()).stream())
                .collect(Collectors.toSet());

        Map<Long, Task> candidates = new LinkedHashMap<>();
        for (AssetCapability cap : caps) {
            for (Task t : taskRepository.findByStatusAndCapabilityRequired(TaskStatus.OPEN, cap)) {
                candidates.putIfAbsent(t.getId(), t);
            }
        }

        Set<Long> taken = taskAssignmentRepository.findByProviderId(providerId).stream()
                .map(TaskAssignment::getTaskId)
                .collect(Collectors.toSet());

        return candidates.values().stream()
                .filter(t -> !taken.contains(t.getId()))
                .filter(t -> withinServiceRadius(t, lat, lng))
                .map(this::toViewWithExtension)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TaskViews.AssignmentView> listForProvider(Long providerId) {
        return taskAssignmentRepository.findByProviderId(providerId).stream()
                .map(this::toAssignmentView)
                .toList();
    }

    @Transactional
    public TaskViews.AssignmentView accept(Long taskId, Long providerId, Long assetId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> BizException.notFound("error.task.not.found"));
        if (task.getStatus() != TaskStatus.OPEN) {
            throw BizException.of(40901, "error.task.not.open");
        }
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> BizException.notFound("error.asset.not.found"));
        if (!Objects.equals(asset.getUserId(), providerId) && !Objects.equals(asset.getOwnerId(), providerId)) {
            throw BizException.forbidden("error.task.asset.not.owned");
        }
        if (!parseCapabilities(asset.getCapabilities()).contains(task.getCapabilityRequired())) {
            throw BizException.invalidParam("error.task.asset.capability");
        }

        TaskAssignment assignment = TaskAssignment.builder()
                .taskId(task.getId())
                .providerId(providerId)
                .assetId(assetId)
                .status(TaskStatus.ASSIGNED)
                .progressPct(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        assignment = taskAssignmentRepository.save(assignment);

        task.setStatus(TaskStatus.ASSIGNED);
        task.setAssignedAt(Instant.now());
        task = taskRepository.save(task);

        return toAssignmentView(assignment);
    }

    /**
     * provider 上报进度：application / task 双方推进到 IN_PROGRESS。
     *
     * <p>终态护栏必须早于任何赋值：本方法原先把 assignment 无条件打成 IN_PROGRESS，
     * 对已 SETTLED 的接单一调用就把 {@code SETTLED → IN_PROGRESS} 直接回退。
     *
     * @throws BizException 40902 error.task.already.terminal（task 或 assignment 处于终态）
     */
    @Transactional
    public TaskViews.AssignmentView updateProgress(Long taskId, Long providerId, TaskRequests.Progress req) {
        TaskAssignment assignment = taskAssignmentRepository.findByTaskIdAndProviderId(taskId, providerId)
                .orElseThrow(() -> BizException.notFound("error.task.assignment.not.found"));
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> BizException.notFound("error.task.not.found"));
        assertNotTerminal(task, assignment);

        assignment.setProgressPct(req.progressPct());
        assignment.setLastProgressNote(req.note());
        assignment.setUpdatedAt(Instant.now());

        if (req.progressPct() != null && req.progressPct() > 0) {
            assignment.setStatus(TaskStatus.IN_PROGRESS);
            if (task.getStatus() == TaskStatus.ASSIGNED) {
                task.setStatus(TaskStatus.IN_PROGRESS);
                taskRepository.save(task);
            }
        }
        assignment = taskAssignmentRepository.save(assignment);
        return toAssignmentView(assignment);
    }

    /**
     * provider 完成任务：双方置 COMPLETED 后立刻结算。
     *
     * <p>终态护栏必须早于任何 setStatus / save：本方法原先<b>先无条件</b>把 task 与 assignment
     * 降回 COMPLETED，再调 {@link TaskSettlementService#settle}，而 settle 的幂等护栏判据是
     * {@code task.status == SETTLED}。于是对已结算任务重复调一次，task 先被降级 → 幂等护栏失效
     * → 二次过账撞 ledger 的 {@code (bizType, bizRef)} 幂等键抛 40950，同时库里 assignment
     * 被打回 COMPLETED，{@code assetEarnings()} 的 {@code status == SETTLED} 对账口径再次落空
     * ——与「settle 不同步 assignment」那个 bug 的后果完全一样：钱过了账，对账却查不到。
     *
     * @throws BizException 40902 error.task.already.terminal（task 或 assignment 处于终态）
     */
    @Transactional
    public TaskViews.AssignmentView complete(Long taskId, Long providerId) {
        TaskAssignment assignment = taskAssignmentRepository.findByTaskIdAndProviderId(taskId, providerId)
                .orElseThrow(() -> BizException.notFound("error.task.assignment.not.found"));
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> BizException.notFound("error.task.not.found"));
        assertNotTerminal(task, assignment);

        assignment.setStatus(TaskStatus.COMPLETED);
        assignment.setFinishedAt(Instant.now());
        assignment.setProgressPct(100);
        assignment.setUpdatedAt(Instant.now());
        assignment = taskAssignmentRepository.save(assignment);

        task.setStatus(TaskStatus.COMPLETED);
        task.setCompletedAt(Instant.now());
        task = taskRepository.save(task);

        taskSettlementService.settle(task, assignment);
        return toAssignmentView(assignment);
    }

    /** 任务视图：按 task_type 附带对应扩展明细 map（LOGISTICS→logistics / 出行→ride / AD→ad）。 */
    private TaskViews.TaskView toViewWithExtension(Task t) {
        Map<String, Object> logistics = null;
        Map<String, Object> ride = null;
        Map<String, Object> ad = null;
        Map<String, Object> drone = null;

        switch (t.getTaskType()) {
            case LOGISTICS -> {
                TaskLogistics l = taskLogisticsRepository.findById(t.getId()).orElse(null);
                if (l != null) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("pickupAddr", l.getPickupAddr());
                    m.put("dropoffAddr", l.getDropoffAddr());
                    m.put("cargoType", l.getCargoType());
                    m.put("weightKg", l.getWeightKg());
                    logistics = m;
                }
            }
            case HAIL_RIDE, TAXI -> {
                TaskRide r = taskRideRepository.findById(t.getId()).orElse(null);
                if (r != null) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("originAddr", r.getOriginAddr());
                    m.put("destAddr", r.getDestAddr());
                    m.put("rideType", r.getRideType());
                    m.put("estDistanceKm", r.getEstDistanceKm());
                    m.put("estDurationMin", r.getEstDurationMin());
                    m.put("fareModel", r.getFareModel());
                    ride = m;
                }
            }
            case AD -> {
                TaskAd a = taskAdRepository.findById(t.getId()).orElse(null);
                if (a != null) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("advertiser", a.getAdvertiser());
                    m.put("mediaUrl", a.getMediaUrl());
                    m.put("displayDuration", a.getDisplayDuration());
                    m.put("screenType", a.getScreenType());
                    ad = m;
                }
            }
            case DRONE_OP -> {
                if (t.getDroneMissionId() != null) {
                    DroneMission d = droneMissionRepository.findById(t.getDroneMissionId()).orElse(null);
                    if (d != null) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("missionType", d.getMissionType() == null ? null : d.getMissionType().name());
                        m.put("payloadDesc", d.getPayloadDesc());
                        m.put("areaHa", d.getAreaHa());
                        m.put("trips", d.getTrips());
                        m.put("flightMinutes", d.getFlightMinutes());
                        m.put("pilotId", d.getPilotId());
                        m.put("assetId", d.getAssetId());
                        drone = m;
                    }
                }
            }
            default -> { }
        }

        return new TaskViews.TaskView(
                t.getId(), t.getPublisherId(), t.getTaskType(), t.getTitle(), t.getDescription(),
                t.getRewardAmount(), t.getCurrency(), t.getCapabilityRequired(), t.getStatus(),
                t.getGeoLat(), t.getGeoLng(), t.getServiceRadiusM(),
                t.getCreatedAt(), t.getDeadlineAt(), t.getAssignedAt(), t.getCompletedAt(), t.getSettledAt(),
                t.getDroneMissionId(),
                logistics, ride, ad, drone);
    }

    /**
     * 终态护栏：task 或 assignment 任一处于终态（SETTLED / CANCELLED）即抛 40902 拒绝流转。
     *
     * <p>为什么两边都要判而不仅判 task：资金已过账的凭证是<b>双方</b>同时为 SETTLED，
     * 而 {@code assetEarnings()} 的对账口径只看 {@code assignment.status == SETTLED}。
     * 只判 task 会漏掉「task 正常、assignment 已结算」这类不一致态。
     *
     * @param task       任务实体，允许为 null
     * @param assignment 接单记录，允许为 null
     * @throws BizException 40902 error.task.already.terminal
     */
    private static void assertNotTerminal(Task task, TaskAssignment assignment) {
        TaskStatus taskStatus = task == null ? null : task.getStatus();
        TaskStatus assignmentStatus = assignment == null ? null : assignment.getStatus();
        if (isTerminal(taskStatus) || isTerminal(assignmentStatus)) {
            throw BizException.of(TASK_ALREADY_TERMINAL, "error.task.already.terminal");
        }
    }

    /** 终态判定（{@link TaskStatus#isTerminal()}），对 null 返回 false 以免 NPE 掩盖真实状态。 */
    private static boolean isTerminal(TaskStatus status) {
        return status != null && status.isTerminal();
    }

    /** TaskAssignment → AssignmentView。 */
    private TaskViews.AssignmentView toAssignmentView(TaskAssignment a) {
        return new TaskViews.AssignmentView(a.getId(), a.getTaskId(), a.getProviderId(), a.getAssetId(),
                a.getStatus(), a.getProgressPct(), a.getLastProgressNote(), a.getStartedAt(), a.getFinishedAt());
    }

    /**
     * 构建 DRONE_OP 任务关联的无人机作业计量记录。
     *
     * @throws BizException missionType 非法、pilotId / assetId 缺失或 executedAt 非 ISO-8601 时
     */
    private static DroneMission buildDroneMission(TaskRequests.Publish req) {
        DroneMissionType missionType = parseMissionType(req.missionType());
        if (req.pilotId() == null) {
            throw BizException.invalidParam("error.task.drone.pilot.required");
        }
        if (req.assetId() == null) {
            throw BizException.invalidParam("error.task.drone.asset.required");
        }
        return DroneMission.builder()
                .assetId(req.assetId())
                .missionType(missionType)
                .payloadDesc(req.payloadDesc())
                .areaHa(req.areaHa())
                .trips(req.trips())
                .flightMinutes(req.flightMinutes())
                .pilotId(req.pilotId())
                .executedAt(parseExecutedAt(req.executedAt()))
                .createdAt(Instant.now())
                .build();
    }

    /** 解析作业类型；空值或不在枚举内一律拒绝（防脏数据落库）。 */
    private static DroneMissionType parseMissionType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.invalidParam("error.task.drone.mission.type");
        }
        try {
            return DroneMissionType.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw BizException.invalidParam("error.task.drone.mission.type");
        }
    }

    /** 解析 ISO-8601 执行时间；缺省取当前时刻，格式非法则拒绝。 */
    private static Instant parseExecutedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        String text = raw.trim();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // 兼容带偏移量的写法（如 2025-01-01T10:00:00+08:00）
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ex) {
            throw BizException.invalidParam("error.task.drone.executed.at");
        }
    }

    /**
     * 地理可接判定：坐标未传入、任务无坐标、任务未设服务半径，或距离在服务半径内均视为可接。
     */
    private static boolean withinServiceRadius(Task t, BigDecimal lat, BigDecimal lng) {
        if (lat == null || lng == null) {
            return true;
        }
        if (t.getGeoLat() == null || t.getGeoLng() == null) {
            return true;
        }
        if (t.getServiceRadiusM() == null) {
            return true;
        }
        return haversineMeters(lat, lng, t.getGeoLat(), t.getGeoLng()) <= t.getServiceRadiusM().doubleValue();
    }

    /** 两点球面距离（米），地球平均半径 6371000 m。 */
    private static double haversineMeters(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        double radLat1 = Math.toRadians(lat1.doubleValue());
        double radLat2 = Math.toRadians(lat2.doubleValue());
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLng = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371000 * c;
    }

    /** 解析 "LOGISTICS,RIDE_HAIL" 形式的能力 CSV 为枚举集合（忽略未知/空项）。 */
    private static Set<AssetCapability> parseCapabilities(String csv) {
        Set<AssetCapability> set = new java.util.HashSet<>();
        if (csv == null || csv.isBlank()) {
            return set;
        }
        for (String part : csv.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                set.add(AssetCapability.valueOf(s));
            } catch (IllegalArgumentException ignored) {
                // 未知能力标签跳过
            }
        }
        return set;
    }
}
