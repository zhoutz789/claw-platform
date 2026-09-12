package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.vpp.VppDispatchOrder;
import com.claw.server.domain.vpp.VppDispatchService;
import com.claw.server.domain.vpp.VppPortfolio;
import com.claw.server.domain.vpp.VppPortfolioRepository;
import com.claw.server.domain.vpp.VppResource;
import com.claw.server.domain.vpp.VppResourceRepository;
import com.claw.server.domain.vpp.VppResourceService;
import com.claw.server.domain.ocpp.OcppCommandService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
@Slf4j
@RestController
@RequestMapping("/api/v1/vpp")
@RequiredArgsConstructor
public class VppController {

    private final VppResourceService vppResourceService;
    private final VppDispatchService vppDispatchService;
    private final VppPortfolioRepository vppPortfolioRepository;
    private final VppResourceRepository vppResourceRepository;
    private final OcppCommandService ocppCommandService;

    /** 当前可调容量（只统计 ONLINE 资源，缺遥测者标记为不可用且不计入总量）。 */
    @GetMapping("/{portfolioId}/capacity")
    public ApiResult<VppResourceService.CapacityView> capacity(@PathVariable Long portfolioId) {
        return ApiResult.ok(vppResourceService.capacity(portfolioId));
    }

    /**
     * 调度建议（影子语义：只生成建议、不落库不下发）。
     *
     * <p>新增显式执行路径：{@code execute=true} 时，对 CHARGER 类指令（SET_CHARGER_POWER /
     * CURTAIL_CHARGER）经 OCPP 层真实下发 {@code SetChargingProfile}（仅充电桩有真实协议，
     * 其它资源保持影子）。执行结果随响应一并返回，单条失败不影响整体计划。
     */
    @RequirePermission("vpp:dispatch")
    @PostMapping("/{portfolioId}/dispatch-plan")
    public ApiResult<DispatchPlanResult> dispatchPlan(
            @PathVariable Long portfolioId,
            @Valid @RequestBody(required = false) DispatchPlanRequest body) {
        boolean exportAllowed = body != null && Boolean.TRUE.equals(body.getExportAllowed());
        boolean execute = body != null && Boolean.TRUE.equals(body.getExecute());
        VppDispatchService.DispatchOutcome plan = vppDispatchService.planForPortfolio(portfolioId, exportAllowed);

        List<OcppCommandService.ExecutionResult> executed = new ArrayList<>();
        if (execute) {
            for (VppDispatchService.CommandDraft c : plan.commands()) {
                if (isChargerCommand(c.commandType())) {
                    try {
                        executed.add(ocppCommandService.apply(c));
                    } catch (BizException e) {
                        log.warn("[VPP] 充电桩指令执行失败 resourceId={}：{}", c.resourceId(), e.getMessage());
                        executed.add(new OcppCommandService.ExecutionResult(
                                c.resourceId(), c.commandType(), null, false, e.getMessage()));
                    }
                }
            }
        }
        return ApiResult.ok(new DispatchPlanResult(plan, executed));
    }

    /** CHARGER 类指令（需经 OCPP 真实下发）。 */
    private static boolean isChargerCommand(String commandType) {
        return VppDispatchService.CMD_SET_CHARGER_POWER.equals(commandType)
                || VppDispatchService.CMD_CURTAIL_CHARGER.equals(commandType);
    }

    /** 调度计划 + 执行结果（execute=true 时携带）。 */
    public record DispatchPlanResult(
            VppDispatchService.DispatchOutcome plan,
            List<OcppCommandService.ExecutionResult> executed) {
    }

    /** 历史指令（含影子标记）。 */
    @GetMapping("/{portfolioId}/orders")
    public ApiResult<List<VppDispatchOrder>> orders(@PathVariable Long portfolioId) {
        return ApiResult.ok(vppDispatchService.listOrders(portfolioId));
    }

    /** 组合列表（含资源数 / 额定总功率概览，供 VppOps 聚合看板；实时可调容量见各组合 /capacity）。 */
    @GetMapping("/portfolios")
    public ApiResult<List<PortfolioSummaryView>> portfolios() {
        List<VppPortfolio> all = vppPortfolioRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        List<PortfolioSummaryView> views = new ArrayList<>();
        for (VppPortfolio p : all) {
            long resourceCount = vppResourceRepository.countByPortfolioId(p.getId());
            BigDecimal totalRatedW = vppResourceRepository.findByPortfolioId(p.getId()).stream()
                    .map(VppResource::getRatedPowerW)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            views.add(new PortfolioSummaryView(p.getId(), p.getName(), p.getOperatorId(), p.getRegionCode(),
                    p.getGridNode(), p.getTargetSelfConsumptionRate(), p.getStatus(), resourceCount, totalRatedW));
        }
        return ApiResult.ok(views);
    }

    /** 调度建议请求体。 */
    @Data
    public static class DispatchPlanRequest {
        /** 光伏余电是否允许上网；缺省 false（无 TOU 电价信号时默认限发）。 */
        private Boolean exportAllowed;

        /** 是否真实下发 CHARGER 指令（经 OCPP 下发 SetChargingProfile）；缺省 false 保持影子。 */
        private Boolean execute;
    }

    /** 组合概览（静态聚合；实时可调容量见 {@code /{portfolioId}/capacity}）。 */
    public record PortfolioSummaryView(
            Long id,
            String name,
            Long operatorId,
            String regionCode,
            String gridNode,
            BigDecimal targetSelfConsumptionRate,
            String status,
            /** 纳入该组合的资源数。 */
            long resourceCount,
            /** 资源额定功率合计 W（含 PV/ESS/CHARGER/DIESEL 等，未配额定者不计）。 */
            BigDecimal totalRatedPowerW) {
    }
}
