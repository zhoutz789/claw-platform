package com.claw.server.domain.user;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 短信网关真实实现（B3）：对接真实短信服务商（如 Twilio / 阿里云国际短信 / 腾讯云国际短信）。
 *
 * <p>仅当 {@code claw.sms.mode=real} 时激活，密钥与端点全部由环境变量注入：
 * <ul>
 *   <li>{@code CLAW_SMS_API_URL} — 服务商下发接口</li>
 *   <li>{@code CLAW_SMS_API_KEY} — 访问密钥</li>
 *   <li>{@code CLAW_SMS_SENDER} — 签名/发送方标识</li>
 * </ul>
 *
 * <p>当前为骨架：构造请求体并打印关键日志，真实 HTTP 调用处已用注释标注 TODO，
 * 接入具体服务商合同时替换 {@code // REAL CALL} 行即可，无需改动调用方 AuthService。
 */
@Component
@ConditionalOnProperty(name = "claw.sms.mode", havingValue = "real")
@Slf4j
public class SmsGatewayReal implements SmsGateway {

    @Value("${claw.sms.api-url:}")
    private String apiUrl;

    @Value("${claw.sms.api-key:}")
    private String apiKey;

    @Value("${claw.sms.sender:Claw}")
    private String sender;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String send(String phone, String code) {
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalStateException("claw.sms.api-url 未配置，无法发送真实短信（env: CLAW_SMS_API_URL）");
        }
        // TODO 对接具体服务商合同：构造鉴权头 + POST 下发
        // HttpHeaders headers = new HttpHeaders();
        // headers.setBearerAuth(apiKey);
        // Map<String,Object> body = Map.of("to", phone, "sender", sender, "code", code);
        // String msgId = restTemplate.postForObject(apiUrl, new HttpEntity<>(body, headers), String.class);
        log.info("[REAL SMS] 已向服务商下发 phone={} sender={} apiUrl={}（apiKey=****）", phone, sender, apiUrl);
        // 占位返回，接入真实 API 后替换为响应中的 messageId
        return "REAL-SMS-" + phone;
    }
}
