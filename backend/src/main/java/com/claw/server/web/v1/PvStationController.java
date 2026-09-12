package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.domain.iot.PvStation;
import com.claw.server.domain.iot.PvStationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 光伏电站只读查询（V116 权限收口后的只读端点）。
 *
 * <p>仅提供只读 GET：电站列表。写动作（遥测上报）在 {@link PvTelemetryController}
 * 单独收口（{@code @RequirePermission("pv:telemetry:push")}），按项目约定本控制器不加注解。
 * PLATFORM_ADMIN 通配放行，MANUFACTURER/REGULATOR 可见菜单并可读取。
 *
 * <p>实时功率/SOH 由前端按 assetId 调 {@code GET /api/v1/telemetry/{assetId}} 取快照；
 * 发电量/PR 走 {@link PvTraceController}。本端点只给静态档案（含 {@code ratedPowerWp} 作 PR 分母）。
 */
@RestController
@RequestMapping("/api/v1/pv")
@RequiredArgsConstructor
public class PvStationController {

    private final PvStationRepository pvStationRepository;

    /** 光伏电站列表（监控卡片与 PR 分母来源）。 */
    @GetMapping("/stations")
    public ApiResult<List<PvStation>> stations() {
        return ApiResult.ok(pvStationRepository.findAll(Sort.by(Sort.Direction.ASC, "id")));
    }
}
