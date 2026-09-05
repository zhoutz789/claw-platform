package com.claw.server.web.v1;

import com.claw.server.common.api.BizException;
import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.StationRequests;
import com.claw.server.common.dto.StationViews;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.station.StationOpsService;
import com.claw.server.domain.station.StationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 站点接口（客户选购入口 + 资产投放入口 + 服务站作业 S4）。
 *
 * <p>GET  /stations/nearby                 附近站点（?countryCode=&lat=&lng=&limit=3）
 * GET  /stations/map                      地图适配层（附近换电站 + 满电/充电中电池数）
 * GET  /stations/search?sku=&lat=&lng=    搜车型 → 附近有现货的站点
 * GET  /stations/{id}/stock               站内现货
 * POST /stations/{id}/stock               投放现货（资产入站）
 * POST /stations/{id}/scan-out            服务站扫码发放满电电池
 * POST /stations/{id}/scan-in             服务站扫码回收欠电电池
 * GET  /stations/{id}/slots               服务站电池位
 * GET  /stations/{id}/daily-bill          服务站日账单（?date=2026-08-20）
 */
@RestController
@RequestMapping("/api/v1/stations")
@RequiredArgsConstructor
public class StationController {

    private final StationService stationService;
    private final StationOpsService stationOpsService;

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

    // ------------------------------------------------------------------
    // 服务站作业（S4）
    // ------------------------------------------------------------------
    @PostMapping("/{id}/scan-out")
    public ApiResult<StationViews.HandoverView> scanOut(@PathVariable Long id,
                                                        @Valid @RequestBody StationRequests.ScanOut req) {
        return ApiResult.ok(stationOpsService.scanOut(id, req, requireOperator()));
    }

    @PostMapping("/{id}/scan-in")
    public ApiResult<StationViews.HandoverView> scanIn(@PathVariable Long id,
                                                       @Valid @RequestBody StationRequests.ScanIn req) {
        return ApiResult.ok(stationOpsService.scanIn(id, req, requireOperator()));
    }

    @GetMapping("/{id}/slots")
    public ApiResult<List<StationViews.SlotView>> slots(@PathVariable Long id) {
        return ApiResult.ok(stationOpsService.slots(id));
    }

    @GetMapping("/{id}/daily-bill")
    public ApiResult<StationViews.DailyBillView> dailyBill(@PathVariable Long id,
                                                           @RequestParam(required = false) LocalDate date) {
        return ApiResult.ok(stationOpsService.dailyBill(id, date));
    }

    /**
     * 追加保证金升档（缺口①）：服务站追加保证金 → 选更高档位 → 授信 ×4 放大、项目扩大、旧约续签。
     * 仅平台运营可调，需 operator 身份（未登录返回 401）。
     */
    @PostMapping("/{id}/upgrade-tier")
    public ApiResult<StationService.StationUpgradeResult> upgradeTier(@PathVariable Long id,
                                                                     @RequestParam Long newTierId) {
        return ApiResult.ok(stationService.upgradeTier(id, newTierId, requireOperator()));
    }

    /**
     * 取当前登录用户 ID；无认证上下文时返回 401（不是 500）。
     *
     * <p>此前抛 IllegalStateException("unauthenticated")，而全局异常处理器没有对应
     * handler —— 会兜成 500 + "internal error"。未登录是客户端问题，正确语义是 401：
     * 客户端据此跳登录页，而不是展示「服务异常」。
     *
     * <p><b>为什么 E2E 抓不到</b>：dev-open-access=true 会注入虚拟操作员 id=1，
     * uid == null 这条路径走不到，所以开着 dev 开关冒烟永远是绿的。验证必须关掉该开关，
     * 见 scripts/e2e-smoke.sh 的未认证探测轮。
     */
    private Long requireOperator() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            throw BizException.unauthorized("error.auth.unauthenticated");
        }
        return uid;
    }
}
