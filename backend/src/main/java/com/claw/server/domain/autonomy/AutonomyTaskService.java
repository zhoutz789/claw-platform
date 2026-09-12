package com.claw.server.domain.autonomy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 无人车任务派发服务（AU3）：创建任务、上报进度、按资产查任务。
 */
@Service
@RequiredArgsConstructor
public class AutonomyTaskService {

    private final AutonomyTaskRepository taskRepository;

    /**
     * 派发新任务：默认 PENDING / 进度 0。
     */
    @Transactional
    public AutonomyTask createTask(Long assetId, String subtype, String pathJson) {
        AutonomyTask task = AutonomyTask.builder()
                .assetId(assetId)
                .subtype(subtype)
                .pathJson(pathJson)
                .status("PENDING")
                .progressPct(0)
                .build();
        return taskRepository.save(task);
    }

    /**
     * 上报进度：进度夹取到 0..100；达到 100 视为完成。
     */
    @Transactional
    public AutonomyTask updateProgress(Long taskId, int pct) {
        AutonomyTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("autonomy.task.not.found:" + taskId));
        int clamped = Math.max(0, Math.min(100, pct));
        task.setProgressPct(clamped);
        if (clamped >= 100) {
            task.setStatus("COMPLETED");
        }
        return taskRepository.save(task);
    }

    public List<AutonomyTask> getByAsset(Long assetId) {
        return taskRepository.findByAssetId(assetId);
    }

    /**
     * AU3 派发自主任务：关联 TaskHall 任务（tasks.id = taskId），默认 PENDING / 进度 0。
     */
    @Transactional
    public AutonomyTask dispatch(Long taskId, Long assetId, String subtype, String pathJson) {
        AutonomyTask task = AutonomyTask.builder()
                .taskId(taskId)
                .assetId(assetId)
                .subtype(subtype)
                .pathJson(pathJson)
                .status("PENDING")
                .progressPct(0)
                .build();
        return taskRepository.save(task);
    }

    /**
     * AU3 上报进度（按资产取最近一条任务回写 progress_pct；达 100 视为完成）。
     * 进度夹取到 0..100。
     */
    @Transactional
    public AutonomyTask reportProgress(Long assetId, int progressPct) {
        List<AutonomyTask> tasks = taskRepository.findByAssetId(assetId);
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("autonomy.task.not.found.for.asset:" + assetId);
        }
        AutonomyTask task = tasks.stream()
                .max(Comparator.comparing(AutonomyTask::getId))
                .orElseThrow();
        int clamped = Math.max(0, Math.min(100, progressPct));
        task.setProgressPct(clamped);
        if (clamped >= 100) {
            task.setStatus("COMPLETED");
        }
        return taskRepository.save(task);
    }
}
