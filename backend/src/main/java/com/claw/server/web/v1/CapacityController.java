package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.capacity.CapacityBookingService;
import com.claw.server.domain.capacity.CapacityPlan;
import com.claw.server.domain.capacity.CapacityRebateSettlement;
import com.claw.server.domain.capacity.CapacitySubscription;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户（客户）侧容量预定入口（gap⑥）：预定 / 我的定购 / 开放计划 / 我的回佣。
 *
 * <p>V81：预定动作收敛为「填份数并付款」——计划由前端按钮联动带入，订户身份取登录态，
 * 请求体只剩 {@code unitCount}，不允许前端伪造身份或改写计划参数。
 */
@RestController
@RequestMapping("/api/v1/capacity")
@RequiredArgsConstructor
public class CapacityController {

    private final CapacityBookingService capacityBookingService;

    /**
     * 客户预定容量：填份数并付款（预付产能款直付厂家托管，平台不经手资金池）。
     *
     * <p>V81：订户身份 {@code subscriberUserId} 从登录态带出，不再由前端传 ——
     * 商品 / 计划 / 单价全部由 planId 联动确定，杜绝串单与冒用。
     */
    @PostMapping("/subscribe")
    @RequirePermission("capacity:subscribe")
    public ApiResult<CapacitySubscription> subscribe(@RequestBody SubscribeReq req) {
        Long subscriberUserId = AuthContext.currentUserId();
        if (subscriberUserId == null) {
            throw BizException.of(40301, "error.permission.denied");
        }
        return ApiResult.ok(capacityBookingService.subscribe(req.planId(), subscriberUserId, req.unitCount()));
    }

    /** 我的定购记录。 */
    @GetMapping("/subscriptions")
    public ApiResult<List<CapacitySubscription>> mySubscriptions(@RequestParam Long subscriberUserId) {
        return ApiResult.ok(capacityBookingService.listBySubscriber(subscriberUserId));
    }

    /** 资产当前开放容量计划。 */
    @GetMapping("/plans/open")
    public ApiResult<CapacityPlan> openPlan(@RequestParam Long assetId) {
        return ApiResult.ok(capacityBookingService.findOpenPlan(assetId));
    }

    /** 我的回佣结算明细。 */
    @GetMapping("/rebates")
    public ApiResult<List<CapacityRebateSettlement>> myRebates(@RequestParam Long subscriberUserId) {
        return ApiResult.ok(capacityBookingService.listRebatesBySubscriber(subscriberUserId));
    }

    /**
     * 预定入参（V81 精简版）：计划由前端按钮联动带入，订户取登录态，这里只填份数。
     *
     * @param planId    容量计划 id（必填）
     * @param unitCount 预定份数（必填，&gt; 0）
     */
    public record SubscribeReq(Long planId, Integer unitCount) {
    }
}
