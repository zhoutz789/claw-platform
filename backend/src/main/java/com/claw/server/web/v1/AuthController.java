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

    /** 当前登录态探测（需鉴权）。 */
    @GetMapping("/me")
    public ApiResult<Long> me() {
        return ApiResult.ok(AuthContext.currentUserId());
    }
}
