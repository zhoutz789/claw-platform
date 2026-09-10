package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.TaskRequests;
import com.claw.server.common.dto.TaskViews;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.task.TaskService;
import com.claw.server.domain.task.TaskSettlementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 任务大厅控制器（P0：LOGISTICS 闭环）。
 *
 * <p>所有端点强制登录（uid()），不挂任何 {@code @RequirePermission}（P2 才注册 task:publish 等权限位，
 * 当前未 seed 会导致全员 403）。资金走 TaskSettlementService → LedgerService 双记账，本类不碰账本。
 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskSettlementService taskSettlementService;

    /** 发布任务。 */
    @PostMapping
    public ApiResult<TaskViews.TaskView> publish(@Valid @RequestBody TaskRequests.Publish req) {
        return ApiResult.ok(taskService.publish(req, uid()));
    }

    /** 任务列表。role=provider 返回可接任务，否则返回我发布的任务。 */
    @GetMapping
    public ApiResult<List<TaskViews.TaskView>> list(@RequestParam(required = false) String role,
                                                    @RequestParam(required = false) String type,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) BigDecimal lat,
                                                    @RequestParam(required = false) BigDecimal lng) {
        if ("provider".equals(role)) {
            return ApiResult.ok(taskService.listAvailableForProvider(uid(), lat, lng));
        }
        return ApiResult.ok(taskService.listForPublisher(uid()));
    }

    /** 任务详情（LOGISTICS 含 logistics 明细）。 */
    @GetMapping("/{id}")
    public ApiResult<TaskViews.TaskView> detail(@PathVariable Long id) {
        return ApiResult.ok(taskService.detail(id));
    }

    /** 接单（绑定资产）。 */
    @PostMapping("/{id}/accept")
    public ApiResult<TaskViews.AssignmentView> accept(@PathVariable Long id,
                                                      @Valid @RequestBody TaskRequests.Accept req) {
        return ApiResult.ok(taskService.accept(id, uid(), req.assetId()));
    }

    /** 进度上报。 */
    @PostMapping("/{id}/progress")
    public ApiResult<TaskViews.AssignmentView> progress(@PathVariable Long id,
                                                        @Valid @RequestBody TaskRequests.Progress req) {
        return ApiResult.ok(taskService.updateProgress(id, uid(), req));
    }

    /** 完成任务并触发结算。 */
    @PostMapping("/{id}/complete")
    public ApiResult<TaskViews.AssignmentView> complete(@PathVariable Long id) {
        return ApiResult.ok(taskService.complete(id, uid()));
    }

    /** 资产收益对账（仅资产归属人可查）。 */
    @GetMapping("/assets/{assetId}/task-earnings")
    public ApiResult<List<TaskViews.TaskEarningView>> assetEarnings(@PathVariable Long assetId) {
        return ApiResult.ok(taskSettlementService.assetEarnings(assetId, uid()));
    }

    /** 我的任务（发布 + 已接）。 */
    @GetMapping("/me/my-tasks")
    public ApiResult<TaskViews.MyTasksView> myTasks() {
        return ApiResult.ok(new TaskViews.MyTasksView(
                taskService.listForPublisher(uid()), taskService.listForProvider(uid())));
    }

    /** 取当前登录用户 ID；无认证上下文返回 401（而非 500）。 */
    private Long uid() {
        Long id = AuthContext.currentUserId();
        if (id == null) {
            throw BizException.unauthorized("error.auth.unauthenticated");
        }
        return id;
    }
}
