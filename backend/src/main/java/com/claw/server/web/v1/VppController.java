package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.vpp.VppDispatchOrder;
import com.claw.server.domain.vpp.VppDispatchService;
import com.claw.server.domain.vpp.VppResourceService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 虚拟电厂只读/影子入口（VPP 切片第一批）。
 *
 * <p><b>权限收口（V116）：</b>{@code POST /{portfolioId}/dispatch-plan} 已加
 * {@code @RequirePermission("vpp:dispatch")}（生成调度建议/指令属写动作）；
 * {@code GET capacity / orders} 为只读，按项目约定不加注解。权限位与菜单由 V116 迁移播种，
 * PLATFORM_ADMIN 通配放行，MANUFACTURER/REGULATOR 仅可见菜单、无写权限。
 *
 * <p>三个入口均为只读或影子语义：{@code dispatch-plan} 只返回建议、不落库不下发；
 * {@code orders} 只读历史指令。真实下发在本批次不可达（影子开关缺省开）。
 *
 * <p>错误一律抛 {@code BizException}，不返回 {@code ApiResult.error(...)}
 * （后者是 HTTP 200 包错误体，前端无法按状态码分流，项目已踩过）。
 */
@RestController
@RequestMapping("/api/v1/vpp")
@RequiredArgsConstructor
public class VppController {

    private final VppResourceService vppResourceService;
    private final VppDispatchService vppDispatchService;

    /** 当前可调容量（只统计 ONLINE 资源，缺遥测者标记为不可用且不计入总量）。 */
    @GetMapping("/{portfolioId}/capacity")
    public ApiResult<VppResourceService.CapacityView> capacity(@PathVariable Long portfolioId) {
        return ApiResult.ok(vppResourceService.capacity(portfolioId));
    }

    /** 调度建议（不落库、不下发）。 */
    @RequirePermission("vpp:dispatch")
    @PostMapping("/{portfolioId}/dispatch-plan")
    public ApiResult<VppDispatchService.DispatchOutcome> dispatchPlan(
            @PathVariable Long portfolioId,
            @Valid @RequestBody(required = false) DispatchPlanRequest body) {
        boolean exportAllowed = body != null && Boolean.TRUE.equals(body.getExportAllowed());
        return ApiResult.ok(vppDispatchService.planForPortfolio(portfolioId, exportAllowed));
    }

    /** 历史指令（含影子标记）。 */
    @GetMapping("/{portfolioId}/orders")
    public ApiResult<List<VppDispatchOrder>> orders(@PathVariable Long portfolioId) {
        return ApiResult.ok(vppDispatchService.listOrders(portfolioId));
    }

    /** 调度建议请求体。 */
    @Data
    public static class DispatchPlanRequest {
        /** 光伏余电是否允许上网；缺省 false（无 TOU 电价信号时默认限发）。 */
        private Boolean exportAllowed;
    }
}
