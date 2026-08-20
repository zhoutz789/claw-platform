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
}
