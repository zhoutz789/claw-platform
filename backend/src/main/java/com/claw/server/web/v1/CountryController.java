package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.JurisdictionViews;
import com.claw.server.common.enums.JurisdictionStatus;
import com.claw.server.domain.jurisdiction.JurisdictionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 全球化运营 / 法域开放接口。
 *
 * <p>GET /countries            国家清单（可按 status 过滤：PILOT/ACTIVE/PLANNED/EXCLUDED）
 * GET /countries/{code}       某国详情（含身份/支付/牌照适配器摘要）
 * GET /jurisdictions/me       当前请求所属法域完整适配摘要（由 X-Country-Code 决定，缺省 KHM）
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CountryController {

    private final JurisdictionService jurisdictionService;

    /** 国家清单（共营扩展路线图）。 */
    @GetMapping("/countries")
    public ApiResult<List<JurisdictionViews.CountryView>> listCountries(
            @RequestParam(required = false) JurisdictionStatus status) {
        return ApiResult.ok(jurisdictionService.listCountries(status));
    }

    /** 某国详情（含适配器摘要）。 */
    @GetMapping("/countries/{code}")
    public ApiResult<JurisdictionViews.CountryView> country(@PathVariable String code) {
        return ApiResult.ok(jurisdictionService.countryView(code.toUpperCase()));
    }

    /** 当前法域完整摘要（当前试点/扩展状态一览）。 */
    @GetMapping("/jurisdictions/me")
    public ApiResult<JurisdictionViews.CurrentJurisdictionView> me() {
        return ApiResult.ok(jurisdictionService.current());
    }
}
