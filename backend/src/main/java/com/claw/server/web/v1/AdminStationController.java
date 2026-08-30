package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.StationViews;
import com.claw.server.domain.station.StationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台站点接口（管理端下拉选项数据源）。
 *
 * <p>GET /api/v1/admin/stations  全量站点列表（跨国家、含非 ACTIVE 站点）
 *
 * <p>存在原因：增量 B 的调拨单 / 履约订单 / 取货扫码等页面都要选服务站，此前管理端只能
 * 复用客户端的 {@code GET /api/v1/stations/nearby?limit=200} 兜底 —— 该接口按当前国家过滤
 * 且只返回 ACTIVE 站点，跨国运营与停用站点场景下拉选项会缺失。此处补一个 admin 全量接口。
 */
@RestController
@RequestMapping("/api/v1/admin/stations")
@RequiredArgsConstructor
public class AdminStationController {

    private final StationService stationService;

    @GetMapping
    public ApiResult<List<StationViews.StationView>> list() {
        return ApiResult.ok(stationService.listAll());
    }
}
