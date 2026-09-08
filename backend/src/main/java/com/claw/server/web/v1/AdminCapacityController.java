package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.capacity.CapacityBookingService;
import com.claw.server.domain.capacity.CapacityPlan;
import com.claw.server.domain.capacity.CapacitySubscription;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 后台容量预定管理（V71 缺口⑤ + gap⑥ 入口，V81 简化为「按商品」）。
 *
 * <p>V81 改造要点（老板反馈：按钮点开就要联动好，不能让使用者手填一堆裸 ID）：
 * <ul>
 *   <li>建计划入参从 9 个手填字段收敛为 5 个：商品、总份数、单价、窗口（选）、计划说明；
 *       ownerUserId 取登录态、capacityType 固定 PARALLEL、rebateRate 取系统配置默认。</li>
 *   <li>{@code GET /plans?productId=} 按商品取计划（前端取第一条）；</li>
 *   <li>{@code GET /plans/{id}/subscriptions} 取该计划的预定订单（客户侧/厂家侧只读展示）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/capacity")
@RequiredArgsConstructor
public class AdminCapacityController {

    private final CapacityBookingService capacityBookingService;

    /**
     * 发布容量预定计划（挂在指定商品上）。
     *
     * <p>入参只留使用者必须决定的 5 项：productId / totalUnits / unitPrice / 窗口 / 计划说明。
     * 发布方（ownerUserId）从登录态带出，回佣率与容量类型由系统给出，保证同一商品口径一致。
     */
    @PostMapping("/plans")
    @RequirePermission("mfg:capacity:create")
    public ApiResult<CapacityPlan> createPlan(@RequestBody CreatePlanReq req) {
        Long ownerUserId = AuthContext.currentUserId();
        if (ownerUserId == null) {
            throw BizException.of(40301, "error.permission.denied");
        }
        return ApiResult.ok(capacityBookingService.createPlan(req.productId(), ownerUserId,
                req.totalUnits(), req.unitPrice(), req.windowStart(), req.windowEnd(), req.planDesc()));
    }

    /**
     * 查容量计划。
     *
     * <p>带 {@code productId} → 按商品查（前端「容量预定」按钮点开即查，取第一条展示）；
     * 不带 → 返回<b>当前登录用户自己发布</b>的计划（只读总览页用）。
     * 两种入口都不要求使用者手填任何 ID。
     */
    @GetMapping("/plans")
    @RequirePermission("mfg:capacity:view")
    public ApiResult<List<CapacityPlan>> listPlans(@RequestParam(required = false) Long productId) {
        if (productId != null) {
            return ApiResult.ok(capacityBookingService.listPlansByProduct(productId));
        }
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            return ApiResult.ok(List.of());
        }
        return ApiResult.ok(capacityBookingService.listPlans(uid));
    }

    /**
     * 该容量计划下的预定订单列表（只读表格：订户 / 份数 / 金额 / 付款时间）。
     */
    @GetMapping("/plans/{id}/subscriptions")
    @RequirePermission("mfg:capacity:view")
    public ApiResult<List<CapacitySubscription>> planSubscriptions(@PathVariable Long id) {
        return ApiResult.ok(capacityBookingService.listSubscriptionsByPlan(id));
    }

    /**
     * 建计划入参（V81 精简版）。
     *
     * @param productId   关联商品（必填，由「容量预定」按钮联动带入）
     * @param totalUnits  总容量份数（必填）
     * @param unitPrice   每份单价（必填）
     * @param windowStart 预定窗口起（选填）
     * @param windowEnd   预定窗口止（选填）
     * @param planDesc    计划说明：风险提示 + 操作方法（必填，客户侧只读展示）
     */
    public record CreatePlanReq(Long productId, Integer totalUnits, BigDecimal unitPrice,
                                Instant windowStart, Instant windowEnd, String planDesc) {
    }
}
