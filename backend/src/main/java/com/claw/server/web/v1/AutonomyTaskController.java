package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.domain.autonomy.AutonomyTask;
import com.claw.server.domain.autonomy.AutonomyTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 无人车自主任务派发端口（T8 / AU3）。
 *
 * <p>任务不存在时服务抛裸 {@code IllegalArgumentException}：
 * <ul>
 *   <li>40463 → HTTP 404，按 taskId 更新进度时任务不存在；</li>
 *   <li>40464 → HTTP 404，按资产上报进度时该资产暂无任务。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/vehicles/{vehicleId}/autonomy/tasks")
@RequiredArgsConstructor
public class AutonomyTaskController {

    private final AutonomyTaskService service;

    /** 某资产的全量自主任务。 */
    @GetMapping
    public ApiResult<List<AutonomyTask>> listByAsset(@PathVariable Long vehicleId) {
        return ApiResult.ok(service.getByAsset(vehicleId));
    }

    /** 创建自主任务（默认 PENDING / 进度 0）。 */
    @PostMapping
    public ApiResult<AutonomyTask> createTask(@PathVariable Long vehicleId, @RequestBody CreateTask req) {
        return ApiResult.ok(service.createTask(vehicleId, req.subtype(), req.pathJson()));
    }

    /** 派发自主任务（关联 TaskHall 任务）。 */
    @PostMapping("/dispatch")
    public ApiResult<AutonomyTask> dispatch(@PathVariable Long vehicleId, @RequestBody DispatchTask req) {
        return ApiResult.ok(service.dispatch(req.taskId(), vehicleId, req.subtype(), req.pathJson()));
    }

    /**
     * 按 taskId 回写进度。
     *
     * @throws BizException 40463 error.vehicle.autonomy.task.not.found（任务不存在）
     */
    @PostMapping("/{taskId}/progress")
    public ApiResult<AutonomyTask> updateProgress(@PathVariable Long vehicleId, @PathVariable Long taskId,
                                                  @RequestBody ProgressReq req) {
        try {
            return ApiResult.ok(service.updateProgress(taskId, req.pct()));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("autonomy.task.not.found")) {
                throw BizException.of(40463, "error.vehicle.autonomy.task.not.found", taskId);
            }
            throw ex;
        }
    }

    /**
     * 按资产上报最近一条任务进度。
     *
     * @throws BizException 40464 error.vehicle.autonomy.task.not.found.for.asset（该资产暂无任务）
     */
    @PostMapping("/report-progress")
    public ApiResult<AutonomyTask> reportProgress(@PathVariable Long vehicleId, @RequestBody ProgressReq req) {
        try {
            return ApiResult.ok(service.reportProgress(vehicleId, req.pct()));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("autonomy.task.not.found.for.asset")) {
                throw BizException.of(40464, "error.vehicle.autonomy.task.not.found.for.asset", vehicleId);
            }
            throw ex;
        }
    }

    public record CreateTask(String subtype, String pathJson) {
    }

    public record DispatchTask(Long taskId, String subtype, String pathJson) {
    }

    public record ProgressReq(int pct) {
    }
}
