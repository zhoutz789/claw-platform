package com.claw.server.domain.user;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 短信网关 Mock 实现（B3 默认）：仅记录日志并返回伪消息 ID，不真正下发短信。
 *
 * <p>通过 {@code claw.sms.mode=mock}（缺省即 mock）激活；生产设为 {@code real} 切换真实网关。
 */
@Component
@ConditionalOnProperty(name = "claw.sms.mode", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class SmsGatewayMock implements SmsGateway {

    @Override
    public String send(String phone, String code) {
        String msgId = "MOCK-SMS-" + phone + "-" + System.nanoTime();
        log.info("[MOCK SMS] 向 {} 下发验证码 {}（msgId={}，未真实发送）", phone, code, msgId);
        return msgId;
    }
}
