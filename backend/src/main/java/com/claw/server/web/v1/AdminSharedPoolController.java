package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.domain.sharedpool.RentalOrder;
import com.claw.server.domain.sharedpool.SharedPoolEntry;
import com.claw.server.domain.sharedpool.SharedPoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import com.claw.server.common.security.RequirePermission;

/**
 * 后台共享池模块（Phase2 接线）：入池 + 租赁 + 完成分账 + 出池 + 列表。
 * 此前 SharedPoolService 真实业务逻辑因无 controller 入口而不可达，本控制器将其暴露为 API。
 */
@RestController
@RequestMapping("/api/v1/admin/shared-pool")
@RequiredArgsConstructor
public class AdminSharedPoolController {

    private final SharedPoolService sharedPoolService;

    @PostMapping("/entries")
    @RequirePermission("shared-pool:create")
    public ApiResult<SharedPoolEntry> poolAsset(@RequestBody PoolReq req) {
        return ApiResult.ok(sharedPoolService.poolAsset(req.assetId(), req.ownerUserId(), req.stationId(),
                req.ownerSplitRate(), req.stationSplitRate(), req.dailyUsageFee(), req.perSwapFee()));
    }

    @PostMapping("/rentals")
    @RequirePermission("shared-pool:create")
    public ApiResult<RentalOrder> createRental(@RequestBody RentalReq req) {
        return ApiResult.ok(sharedPoolService.createRental(req.assetId(), req.renterUserId(),
                req.stationId(), req.rentalType(), req.poolEntryId()));
    }

    @PostMapping("/rentals/{id}/complete")
    @RequirePermission("shared-pool:create")
    public ApiResult<RentalOrder> completeRental(@PathVariable Long id, @RequestParam BigDecimal totalFee) {
        return ApiResult.ok(sharedPoolService.completeRental(id, totalFee));
    }

    @PostMapping("/entries/{id}/remove")
    @RequirePermission("shared-pool:create")
    public ApiResult<SharedPoolEntry> removeFromPool(@PathVariable Long id) {
        return ApiResult.ok(sharedPoolService.removeFromPool(id));
    }

    @GetMapping("/available")
    public ApiResult<List<SharedPoolEntry>> available(@RequestParam Long stationId) {
        return ApiResult.ok(sharedPoolService.listAvailableAtStation(stationId));
    }

    @GetMapping("/owner")
    public ApiResult<List<SharedPoolEntry>> ownerEntries(@RequestParam Long ownerUserId) {
        return ApiResult.ok(sharedPoolService.listOwnerEntries(ownerUserId));
    }

    @GetMapping("/renter")
    public ApiResult<List<RentalOrder>> renterOrders(@RequestParam Long renterUserId) {
        return ApiResult.ok(sharedPoolService.listRenterOrders(renterUserId));
    }

    public record PoolReq(Long assetId, Long ownerUserId, Long stationId, BigDecimal ownerSplitRate,
                          BigDecimal stationSplitRate, BigDecimal dailyUsageFee, BigDecimal perSwapFee) {
    }

    public record RentalReq(Long assetId, Long renterUserId, Long stationId, String rentalType, Long poolEntryId) {
    }
}
