package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.DashboardViews;
import com.claw.server.domain.report.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台数据大屏接口（S5）。
 *
 * <p>GET /admin/dashboard   总换电单 / 满电充电中电池 / 三专户余额
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/dashboard")
    public ApiResult<DashboardViews.DashboardView> dashboard() {
        return ApiResult.ok(dashboardService.dashboard());
    }
}
