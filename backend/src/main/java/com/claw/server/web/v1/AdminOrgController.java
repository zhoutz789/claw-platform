package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.credit.CreditLimitService;
import com.claw.server.domain.credit.CreditUsageView;
import com.claw.server.domain.onboarding.OnboardingDeposit;
import com.claw.server.domain.onboarding.OnboardingDepositService;
import com.claw.server.domain.onboarding.OnboardingOrgStatusLog;
import com.claw.server.domain.org.OrgGovernanceService;
import com.claw.server.domain.org.OrgGovernanceView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 组织治理后台接口（增量 C · 页面 9 · O36）。
 *
 * <p>服务站 / 厂家 / 商家三类主体统一治理：查看组织状态与额度、禁用 / 启用（原因必填 + 留痕）、
 * 改档（重算授信额度）、查看保证金记录与状态变更历史。
 *
 * <p>权限码：{@code org:status:manage} / {@code org:credit:view}。
 */
@RestController
@RequestMapping("/api/v1/admin/orgs")
@RequiredArgsConstructor
public class AdminOrgController {

    private final OrgGovernanceService orgGovernanceService;
    private final CreditLimitService creditLimitService;
    private final OnboardingDepositService depositService;

    /** 组织管理列表（v_org_governance 投影，按主体类型 / 入驻状态筛选）。 */
    @GetMapping
    public ApiResult<List<OrgGovernanceView>> listOrgs(@RequestParam(required = false) String principalType,
                                                       @RequestParam(required = false) String onboardingStatus) {
        return ApiResult.ok(orgGovernanceService.listOrgs(principalType, onboardingStatus));
    }

    /** 单个组织。 */
    @GetMapping("/{type}/{id}")
    public ApiResult<OrgGovernanceView> getOrg(@PathVariable String type, @PathVariable Long id) {
        return ApiResult.ok(orgGovernanceService.findOrg(PrincipalType.of(type), id)
                .orElseThrow(() -> new com.claw.server.common.api.BizException(40401, "org.not.found")));
    }

    /**
     * 禁用组织（原因<b>必填</b>，前端需二次确认）。
     *
     * <p>禁用后：只切断新增（新建履约单 / 建调拨单 / 铺货入站 / 新建子账号 / 再申请入驻），
     * <b>在途订单继续履约到底</b>（Q7 拍板）。
     */
    @PostMapping("/{type}/{id}/disable")
    public ApiResult<Void> disable(@PathVariable String type, @PathVariable Long id,
                                   @RequestBody ReasonReq req) {
        orgGovernanceService.disable(PrincipalType.of(type), id, req.reason(), currentUserId());
        return ApiResult.ok();
    }

    /** 启用组织。 */
    @PostMapping("/{type}/{id}/enable")
    public ApiResult<Void> enable(@PathVariable String type, @PathVariable Long id,
                                  @RequestBody(required = false) ReasonReq req) {
        orgGovernanceService.enable(PrincipalType.of(type), id, req == null ? null : req.reason(),
                currentUserId());
        return ApiResult.ok();
    }

    /** 改档（重算授信额度；降档导致存量超额时只提示不阻断）。 */
    @PostMapping("/{type}/{id}/tier")
    public ApiResult<Void> changeTier(@PathVariable String type, @PathVariable Long id,
                                      @RequestBody TierReq req) {
        orgGovernanceService.changeTier(PrincipalType.of(type), id, req.tierId(), req.reason(),
                currentUserId());
        return ApiResult.ok();
    }

    /** 授信额度占用详情（已用 / 上限 / 占用率 / 是否超额）。 */
    @GetMapping("/{type}/{id}/credit")
    public ApiResult<CreditUsageView> creditUsage(@PathVariable String type, @PathVariable Long id) {
        PrincipalType pt = PrincipalType.of(type);
        // 首期额度口径为站级（Q17 推荐默认 STATION），其余主体返回空占用
        if (pt != PrincipalType.STATION) {
            return ApiResult.ok(new CreditUsageView(java.math.BigDecimal.ZERO, null, null, null, null,
                    0, Boolean.FALSE, Boolean.FALSE));
        }
        return ApiResult.ok(creditLimitService.creditUsage(id));
    }

    /** 组织的保证金缴款记录。 */
    @GetMapping("/{type}/{id}/deposits")
    public ApiResult<List<OnboardingDeposit>> deposits(@PathVariable String type, @PathVariable Long id) {
        return ApiResult.ok(depositService.listByPrincipal(PrincipalType.of(type), id));
    }

    /** 组织状态变更历史（禁用 / 启用 / 改档留痕）。 */
    @GetMapping("/{type}/{id}/status-logs")
    public ApiResult<List<OnboardingOrgStatusLog>> statusLogs(@PathVariable String type, @PathVariable Long id) {
        return ApiResult.ok(orgGovernanceService.statusHistory(PrincipalType.of(type), id));
    }

    private static Long currentUserId() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            throw new com.claw.server.common.api.BizException(40301, "login.required");
        }
        return uid;
    }

    /** 原因请求（禁用时必填）。 */
    public record ReasonReq(String reason) {
    }

    /** 改档请求。 */
    public record TierReq(Long tierId, String reason) {
    }
}
