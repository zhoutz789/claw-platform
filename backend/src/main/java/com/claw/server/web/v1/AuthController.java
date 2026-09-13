package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.AuthRequests;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.user.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证：短信验证码登录（OTP）。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 发送验证码（dev 回显）。 */
    @PostMapping("/sms-code")
    public ApiResult<String> sendSmsCode(@Valid @RequestBody AuthRequests.SendSmsCode req) {
        return ApiResult.ok(authService.sendSmsCode(req.phone()));
    }

    /** 验证码登录 / 注册。 */
    @PostMapping("/login")
    public ApiResult<ApiViews.AuthResp> login(@Valid @RequestBody AuthRequests.Login req) {
        return ApiResult.ok(authService.login(req.phone(), req.code()));
    }

    /** 账号 + 密码登录（需账号已设置密码）。 */
    @PostMapping("/password-login")
    public ApiResult<ApiViews.AuthResp> passwordLogin(@Valid @RequestBody AuthRequests.PasswordLogin req) {
        return ApiResult.ok(authService.loginWithPassword(req.phone(), req.password()));
    }

    /** 短信验证码找回 / 重置密码（修改密码的手机号验证等价入口）。 */
    @PostMapping("/forgot-password")
    public ApiResult<Void> forgotPassword(@Valid @RequestBody AuthRequests.ForgotPassword req) {
        authService.resetPassword(req.phone(), req.code(), req.newPassword());
        return ApiResult.ok();
    }

    /** 登录态修改密码：需旧密码（首设可空），且须先经手机号短信验证（走 forgot-password）。 */
    @PostMapping("/change-password")
    public ApiResult<Void> changePassword(@Valid @RequestBody AuthRequests.ChangePassword req) {
        authService.changePassword(AuthContext.currentUserId(), req.oldPassword(), req.newPassword());
        return ApiResult.ok();
    }

    /** 当前登录态详情（扩展：身份 + 语言 + 角色 + 权限位，供前端权限内核消费）。 */
    @GetMapping("/me")
    public ApiResult<ApiViews.AuthMeView> me() {
        return ApiResult.ok(authService.me(AuthContext.currentUserId()));
    }
}
