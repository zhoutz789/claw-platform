package com.claw.server.common.api;

/**
 * 统一响应封装。所有 API 出参一律走该结构，便于三端（Flutter/React/服务站 APP）统一解析。
 *
 * @param code    业务码：0 = 成功；非 0 见 BizException
 * @param message 本地化消息（按 Accept-Language 返回 en/km/zh）
 * @param data    业务数据
 */
public record ApiResult<T>(int code, String message, T data) {

    public static final int SUCCESS = 0;

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(SUCCESS, "ok", data);
    }

    public static ApiResult<Void> ok() {
        return new ApiResult<>(SUCCESS, "ok", null);
    }

    public static <T> ApiResult<T> error(int code, String message) {
        return new ApiResult<>(code, message, null);
    }
}
