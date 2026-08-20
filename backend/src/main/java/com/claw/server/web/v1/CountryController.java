package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.JurisdictionViews;
import com.claw.server.common.enums.JurisdictionStatus;
import com.claw.server.common.enums.NodeRole;
import com.claw.server.domain.jurisdiction.JurisdictionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 全球化运营 / 法域开放接口。
 *
 * <p>GET /countries            国家清单（可按 status / nodeRole 过滤；全球一家，无排除国）
 * GET /countries/{code}       某国详情（含身份/支付/牌照适配器摘要）
 * GET /jurisdictions/me       当前请求所属法域完整适配摘要（由 X-Country-Code 决定，缺省 KHM）
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CountryController {

    private final JurisdictionService jurisdictionService;

    /** 国家清单（全球互通节点；可按运营状态或网络角色过滤）。 */
    @GetMapping("/countries")
    public ApiResult<List<JurisdictionViews.CountryView>> listCountries(
            @RequestParam(required = false) JurisdictionStatus status,
            @RequestParam(required = false) NodeRole nodeRole) {
        return ApiResult.ok(jurisdictionService.listCountries(status, nodeRole));
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
