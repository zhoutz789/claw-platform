package com.claw.server.common.dto;

import jakarta.validation.constraints.NotBlank;

/** 认证相关入参（注册/登录）。 */
public final class AuthRequests {

    private AuthRequests() {
    }

    public static record SendSmsCode(@NotBlank String phone) {
    }

    public static record Login(@NotBlank String phone, @NotBlank String code) {
    }

    /** 账号 + 密码登录。 */
    public static record PasswordLogin(@NotBlank String phone, @NotBlank String password) {
    }

    /** 短信验证码找回 / 重置密码。 */
    public static record ForgotPassword(@NotBlank String phone, @NotBlank String code, @NotBlank String newPassword) {
    }

    /** 登录态修改密码（需旧密码，未设密码时首次设置可空）。 */
    public static record ChangePassword(String oldPassword, @NotBlank String newPassword) {
    }
}
