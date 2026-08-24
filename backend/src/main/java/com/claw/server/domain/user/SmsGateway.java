package com.claw.server.domain.user;

/**
 * 短信网关统一抽象（B3 — 外部依赖对接）。
 *
 * <p>本地/测试默认使用 {@link SmsGatewayMock}（仅日志，不真正下发）；
 * 生产通过 {@code claw.sms.mode=real} 切换到 {@link SmsGatewayReal}，
 * 真实服务商密钥与端点由环境变量注入（{@code CLAW_SMS_API_URL} / {@code CLAW_SMS_API_KEY}）。
 */
public interface SmsGateway {

    /**
     * 发送验证码短信。
     *
     * @param phone 手机号
     * @param code  验证码
     * @return 网关侧消息 ID（mock 返回伪 ID，真实实现返回服务商回执 ID）
     */
    String send(String phone, String code);
}
