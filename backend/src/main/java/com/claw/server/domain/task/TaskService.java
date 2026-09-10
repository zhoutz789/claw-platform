package com.claw.server.domain.task;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.TaskRequests;
import com.claw.server.common.dto.TaskViews;
import com.claw.server.common.enums.AssetCapability;
import com.claw.server.common.enums.TaskStatus;
import com.claw.server.common.enums.TaskType;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
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

    @Transactional
    public TaskViews.AssignmentView updateProgress(Long taskId, Long providerId, TaskRequests.Progress req) {
        TaskAssignment assignment = taskAssignmentRepository.findByTaskIdAndProviderId(taskId, providerId)
                .orElseThrow(() -> BizException.notFound("error.task.assignment.not.found"));
        assignment.setProgressPct(req.progressPct());
        assignment.setLastProgressNote(req.note());
        assignment.setUpdatedAt(Instant.now());

        if (req.progressPct() != null && req.progressPct() > 0) {
            assignment.setStatus(TaskStatus.IN_PROGRESS);
            Task task = taskRepository.findById(taskId)
                    .orElseThrow(() -> BizException.notFound("error.task.not.found"));
            if (task.getStatus() == TaskStatus.ASSIGNED) {
                task.setStatus(TaskStatus.IN_PROGRESS);
                taskRepository.save(task);
            }
        }
        assignment = taskAssignmentRepository.save(assignment);
        return toAssignmentView(assignment);
    }

    @Transactional
    public TaskViews.AssignmentView complete(Long taskId, Long providerId) {
        TaskAssignment assignment = taskAssignmentRepository.findByTaskIdAndProviderId(taskId, providerId)
                .orElseThrow(() -> BizException.notFound("error.task.assignment.not.found"));
        assignment.setStatus(TaskStatus.COMPLETED);
        assignment.setFinishedAt(Instant.now());
        assignment.setProgressPct(100);
        assignment.setUpdatedAt(Instant.now());
        assignment = taskAssignmentRepository.save(assignment);

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> BizException.notFound("error.task.not.found"));
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
            default -> { }
        }

        return new TaskViews.TaskView(
                t.getId(), t.getPublisherId(), t.getTaskType(), t.getTitle(), t.getDescription(),
                t.getRewardAmount(), t.getCurrency(), t.getCapabilityRequired(), t.getStatus(),
                t.getGeoLat(), t.getGeoLng(), t.getServiceRadiusM(),
                t.getCreatedAt(), t.getDeadlineAt(), t.getAssignedAt(), t.getCompletedAt(), t.getSettledAt(),
                logistics, ride, ad);
    }

    /** TaskAssignment → AssignmentView。 */
    private TaskViews.AssignmentView toAssignmentView(TaskAssignment a) {
        return new TaskViews.AssignmentView(a.getId(), a.getTaskId(), a.getProviderId(), a.getAssetId(),
                a.getStatus(), a.getProgressPct(), a.getLastProgressNote(), a.getStartedAt(), a.getFinishedAt());
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
