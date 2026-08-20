package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.StationRequests;
import com.claw.server.common.dto.StationViews;
import com.claw.server.domain.station.StationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 站点接口（客户选购入口 + 投资者投放入口）。
 *
 * <p>GET  /stations/nearby                 附近站点（?countryCode=&lat=&lng=&limit=3）
 * GET  /stations/map                      地图适配层（附近换电站 + 满电/充电中电池数）
 * GET  /stations/search?sku=&lat=&lng=    搜车型 → 附近有现货的站点
 * GET  /stations/{id}/stock               站内现货
 * POST /stations/{id}/stock               投放现货（认购入站）
 */
@RestController
@RequestMapping("/api/v1/stations")
@RequiredArgsConstructor
public class StationController {

    private final StationService stationService;

    @GetMapping("/nearby")
    public ApiResult<List<StationViews.StationView>> nearby(
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) java.math.BigDecimal lat,
            @RequestParam(required = false) java.math.BigDecimal lng,
            @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(stationService.nearby(countryCode, lat, lng, limit));
    }

    /** 地图适配层：附近换电站（距离/满电数/充电中），S3 换电入口。 */
    @GetMapping("/map")
    public ApiResult<List<StationViews.MapView>> map(
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) java.math.BigDecimal lat,
            @RequestParam(required = false) java.math.BigDecimal lng,
            @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(stationService.mapView(countryCode, lat, lng, limit));
    }

    @GetMapping("/search")
    public ApiResult<List<StationViews.SkuHitView>> search(@RequestParam String sku,
                                                           @RequestParam(required = false) String countryCode,
                                                           @RequestParam(required = false) java.math.BigDecimal lat,
                                                           @RequestParam(required = false) java.math.BigDecimal lng,
                                                           @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(stationService.findStationsWithSku(countryCode, sku, lat, lng, limit));
    }

    @GetMapping("/{id}/stock")
    public ApiResult<List<StationViews.StockView>> stockOf(@PathVariable Long id) {
        return ApiResult.ok(stationService.stockOf(id));
    }

    @PostMapping("/{id}/stock")
    public ApiResult<StationViews.StockView> stockIn(@PathVariable Long id,
                                                     @Valid @RequestBody StationRequests.StockIn req) {
        return ApiResult.ok(stationService.stockIn(id, req.skuCode(), req.qty()));
    }
}
