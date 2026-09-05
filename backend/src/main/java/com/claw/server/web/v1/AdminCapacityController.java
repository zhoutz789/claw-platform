package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.enums.CapacityType;
import com.claw.server.domain.capacity.CapacityBookingService;
import com.claw.server.domain.capacity.CapacityPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 后台容量预订管理（缺口⑤ + gap⑥ 入口）：发布计划 / 列表。
 */
@RestController
@RequestMapping("/api/v1/admin/capacity")
@RequiredArgsConstructor
public class AdminCapacityController {

    private final CapacityBookingService capacityBookingService;

    /** 发布容量预订计划。 */
    @PostMapping("/plans")
    public ApiResult<CapacityPlan> createPlan(@RequestBody CreatePlanReq req) {
        return ApiResult.ok(capacityBookingService.createPlan(req.assetId(), req.poolEntryId(),
                req.ownerUserId(), req.totalUnits(), req.unitPrice(), req.capacityType(),
                req.rebateRate(), req.windowStart(), req.windowEnd()));
    }

    /** 厂家视角：列出自己发布的容量计划。 */
    @GetMapping("/plans")
    public ApiResult<List<CapacityPlan>> listPlans(@RequestParam Long ownerUserId) {
        return ApiResult.ok(capacityBookingService.listPlans(ownerUserId));
    }

    public record CreatePlanReq(Long assetId, Long poolEntryId, Long ownerUserId, Integer totalUnits,
                                BigDecimal unitPrice, CapacityType capacityType, BigDecimal rebateRate,
                                Instant windowStart, Instant windowEnd) {
    }
}
