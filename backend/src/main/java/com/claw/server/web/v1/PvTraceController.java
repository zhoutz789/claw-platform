package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.PvTraceViews;
import com.claw.server.domain.iot.PvTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 光伏追溯查询（光伏追溯切片，只读）。
 *
 * <p>三个端点：组件溯源 / 批次概览 / 电站发电量（含 PR）。
 *
 * <p><b>权限收口（V116）：</b>本控制器三个端点均为只读 GET，按项目约定（读接口一律不动）
 * 不加 {@code @RequirePermission}；权限位与菜单由 V116 迁移播种，PLATFORM_ADMIN 通配放行，
 * MANUFACTURER/REGULATOR 可见菜单并可读取。写动作（遥测上报 / 调度指令）的单测门禁在
 * {@code PvTelemetryController} / {@code VppController} 中单独收口。
 */
@RestController
@RequestMapping("/api/v1/pv/trace")
@RequiredArgsConstructor
public class PvTraceController {

    private final PvTraceService pvTraceService;

    /** 组件溯源：按序列号查档案 + 所在逆变器累计发电量 + 估算衰减。 */
    @GetMapping("/module")
    public ApiResult<PvTraceViews.ModuleTraceView> module(@RequestParam String serialNo) {
        return ApiResult.ok(pvTraceService.moduleTrace(serialNo));
    }

    /** 批次概览：该批次组件数量与明细。 */
    @GetMapping("/batch")
    public ApiResult<PvTraceViews.BatchOverviewView> batch(@RequestParam String batchNo) {
        return ApiResult.ok(pvTraceService.batchOverview(batchNo));
    }

    /** 电站发电量：按天/月汇总，带 PR。 */
    @GetMapping("/station/{assetId}/yield")
    public ApiResult<PvTraceViews.StationYieldView> stationYield(
            @PathVariable Long assetId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResult.ok(pvTraceService.stationYield(assetId, from, to));
    }
}
