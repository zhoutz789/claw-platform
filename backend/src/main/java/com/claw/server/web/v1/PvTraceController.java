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
 * <p><b>权限说明：</b>本切片刻意<b>不加</b>权限注解（{@code @RequirePermission} 等），
 * 与 {@code PvTelemetryController} 保持一致的接入节奏；权限位与菜单由后续「光伏权限收口」
 * 切片统一补齐（届时与遥测上报端点一并加），此处不留半套注解以免产生"已鉴权"的假象。
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
