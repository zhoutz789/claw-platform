package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.dto.KycRequests;
import com.claw.server.common.dto.RoleRequests;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.role.RoleGrantService;
import com.claw.server.domain.role.RoleView;
import com.claw.server.domain.user.AuthService;
import com.claw.server.domain.user.KycService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户：资料、KYC（实名 / CamDigiKey eKYC）、人人经济角色包。
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final KycService kycService;
    private final RoleGrantService roleGrantService;

    /** 我的资料。 */
    @GetMapping("/me")
    public ApiResult<ApiViews.UserProfile> profile() {
        return ApiResult.ok(authService.profile(requireUserId()));
    }

    /** 平台实名认证（MANUAL）。 */
    @PostMapping("/kyc")
    public ApiResult<ApiViews.KycView> kyc(@Valid @RequestBody KycRequests.Manual req) {
        return ApiResult.ok(kycService.submitManual(requireUserId(), req));
    }

    /** CamDigiKey 国家数字身份 eKYC（OAuth2.0 接入点）。 */
    @PostMapping("/kyc/camdigikey")
    public ApiResult<ApiViews.KycView> kycCamdigikey(@Valid @RequestBody KycRequests.Camdigikey req) {
        return ApiResult.ok(kycService.submitCamdigikey(requireUserId(), req));
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

    private Long requireUserId() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            throw new IllegalStateException("unauthenticated");
        }
        return uid;
    }
}
