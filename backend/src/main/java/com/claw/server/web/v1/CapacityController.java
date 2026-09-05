package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.domain.capacity.CapacityBookingService;
import com.claw.server.domain.capacity.CapacityPlan;
import com.claw.server.domain.capacity.CapacityRebateSettlement;
import com.claw.server.domain.capacity.CapacitySubscription;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户侧容量预订入口（gap⑥）：定购 / 我的定购 / 开放计划 / 我的回佣。
 */
@RestController
@RequestMapping("/api/v1/capacity")
@RequiredArgsConstructor
public class CapacityController {

    private final CapacityBookingService capacityBookingService;

    /** 用户定购容量单位（预付产能款直付厂家托管）。 */
    @PostMapping("/subscribe")
    public ApiResult<CapacitySubscription> subscribe(@RequestBody SubscribeReq req) {
        return ApiResult.ok(capacityBookingService.subscribe(req.planId(), req.subscriberUserId(), req.unitCount()));
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

    public record SubscribeReq(Long planId, Long subscriberUserId, Integer unitCount) {
    }
}
