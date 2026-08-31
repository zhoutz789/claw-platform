package com.claw.server.web.v1;

import com.claw.server.common.api.BizException;
import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.dto.KycRequests;
import com.claw.server.common.dto.RoleRequests;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.jurisdiction.JurisdictionService;
import com.claw.server.domain.role.RoleGrantService;
import com.claw.server.domain.role.RoleView;
import com.claw.server.domain.user.AuthService;
import com.claw.server.domain.user.KycService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户：资料、KYC（实名 / 国家数字身份 eKYC）、人人经济角色包。
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final KycService kycService;
    private final RoleGrantService roleGrantService;
    private final JurisdictionService jurisdictionService;

    /** 我的资料。 */
    @GetMapping("/me")
    public ApiResult<ApiViews.UserProfile> profile() {
        return ApiResult.ok(authService.profile(requireUserId()));
    }

    /** 更新我的语言偏好（登录后同步回写账号，便于跨端一致）。 */
    @Transactional
    @PutMapping("/me/locale")
    public ApiResult<Void> updateLocale(@RequestBody LocaleReq req) {
        authService.updateLocale(requireUserId(), req.locale());
        return ApiResult.ok();
    }

    public record LocaleReq(String locale) {
    }

    /** 平台实名认证（MANUAL）。 */
    @PostMapping("/kyc")
    public ApiResult<ApiViews.KycView> kyc(@Valid @RequestBody KycRequests.Manual req) {
        return ApiResult.ok(kycService.submitManual(requireUserId(), req));
    }

    /** 国家数字身份 eKYC（OAuth2.0 接入点）；IdP 须为当前法域注册表中已激活的提供方。 */
    @PostMapping("/kyc/camdigikey")
    public ApiResult<ApiViews.KycView> kycCamdigikey(@Valid @RequestBody KycRequests.Camdigikey req) {
        String providerCode = (req.providerCode() == null || req.providerCode().isBlank())
                ? "CAMDIGIKEY" : req.providerCode();
        jurisdictionService.identityProviders(requireCountry())
                .stream()
                .filter(p -> p.providerCode().equalsIgnoreCase(providerCode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("identity provider not configured for country"));
        return ApiResult.ok(kycService.submitCamdigikey(requireUserId(), req));
    }

    private String requireCountry() {
        return com.claw.server.common.security.CountryContext.countryCode();
    }

    /** 我的角色包列表。 */
    @GetMapping("/me/roles")
    public ApiResult<List<RoleView>> myRoles() {
        return ApiResult.ok(roleGrantService.listActive(requireUserId()));
    }

    /** 申请开通某角色包。 */
    @PostMapping("/me/roles/{code}/apply")
    public ApiResult<RoleView> applyRole(@PathVariable String code, @Valid @RequestBody RoleRequests.Apply req) {
        // req.roleCode 优先，路径 code 兜底
        String roleCode = (req.roleCode() != null && !req.roleCode().isBlank()) ? req.roleCode() : code;
        return ApiResult.ok(roleGrantService.apply(requireUserId(), roleCode));
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
    private Long requireUserId() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            throw BizException.unauthorized("error.auth.unauthenticated");
        }
        return uid;
    }
}
