package com.claw.server.domain.iot;

import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.iot.Device;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * IoT 设备级鉴权回调（供 EMQX HTTP 认证器调用）。
 *
 * <p>EMQX 在建立 MQTT 连接时把 {@code username}/{@code password} 发到本端点；平台校验后返回
 * 200 允许、401 拒绝。覆盖两类身份：
 * <ul>
 *   <li>平台后端服务账号（{@code claw.iot.emqx.server-username}）：用于平台侧订阅/下发；</li>
 *   <li>车辆终端（username = deviceNo，password = secret）：设备级鉴权。</li>
 * </ul>
 * EMQX 5.x HTTP 认证器默认以 {@code application/json} 发送 body（{@code {"username":..,"password":..}}），
 * 部分客户端/测试工具则使用 {@code application/x-www-form-urlencoded}。本端点同时兼容两种格式。
 * 关键约束：ServletRequest 的输入流只能消费一次，因此本控制器在 {@code auth()} 中把 body 只读一次，
 * 之后多次解析复用，避免“多次 getReader → Stream closed → 字段为空 → 401”的坑。
 * 该端点已在 {@code SecurityConfig} 白名单放行（EMQX 无法携带 JWT）。
 */
@RestController
@RequestMapping("/api/v1/iot")
@RequiredArgsConstructor
public class DeviceAuthController {

    private static final Logger log = LoggerFactory.getLogger(DeviceAuthController.class);

    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${claw.iot.emqx.server-username:claw-server}")
    private String serverUsername;

    @Value("${claw.iot.emqx.server-secret:claw-server-secret}")
    private String serverSecret;

    @PostMapping("/auth")
    public ResponseEntity<String> auth(HttpServletRequest request) {
        // 整个请求只读一次 body（输入流只能消费一次），之后多次解析复用。
        String queryString = request.getQueryString();
        String body = readBody(request);
        String username = extractParam(queryString, body, "username");
        String password = extractParam(queryString, body, "password");
        log.info("[AUTH] username={} pwLen={} serverMatch={}", username,
                password == null ? 0 : password.length(),
                Boolean.valueOf(serverUsername.equals(username) && serverSecret.equals(password)));
        if (username == null) {
            return ResponseEntity.status(401).body("{\"result\":\"deny\"}");
        }
        // 1) 平台后端服务账号：用于平台侧订阅上行 / 下发下行
        if (serverUsername.equals(username) && serverSecret.equals(password)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"result\":\"allow\"}");
        }
        // 2) 设备级：deviceNo + secret
        Device device = deviceRepository.findByDeviceNo(username).orElse(null);
        if (device != null && "ACTIVE".equalsIgnoreCase(device.getStatus())
                && device.getSecret() != null && device.getSecret().equals(password)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"result\":\"allow\"}");
        }
        return ResponseEntity.status(401).body("{\"result\":\"deny\"}");
    }

    /**
     * 兼容 EMQX HTTP 认证器的两种入参形态：
     * <ul>
     *   <li>query string / form-urlencoded（部分工具）：从 query 或 body 表单中取；</li>
     *   <li>application/json（EMQX 5.x 默认）：从 body 中解析。</li>
     * </ul>
     * 入参 {@code queryString}/{@code body} 都是已读取好的字符串，不触碰请求流，可安全复用多次。
     */
    private String extractParam(String queryString, String body, String name) {
        // 1) query string（不依赖请求流）
        String fromQs = parseForm(queryString, name);
        if (fromQs != null) {
            return fromQs;
        }
        // 2) body：按内容判断 JSON / form（body 已是字符串，可重复解析）
        if (body == null || body.isBlank()) {
            return null;
        }
        if (contentTypeOf(body).contains("json")) {
            try {
                JsonNode node = objectMapper.readTree(body);
                JsonNode n = node.get(name);
                if (n != null && !n.isNull()) {
                    return n.asText();
                }
            } catch (Exception ignored) {
                // 解析失败交给后续流程（返回 401）。
            }
        } else {
            String fromForm = parseForm(body, name);
            if (fromForm != null) {
                return fromForm;
            }
        }
        return null;
    }

    /** 从 {@code k=v&k=v} 形式的字符串中取指定字段（兼容 query 或 form-urlencoded body）。 */
    private String parseForm(String s, String name) {
        if (s == null || s.isBlank()) {
            return null;
        }
        for (String pair : s.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].trim().equals(name)) {
                try {
                    return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private String readBody(HttpServletRequest request) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = request.getReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /** 依据 body 内容推断类型：JSON 以 '{' 开头；否则按表单处理。 */
    private String contentTypeOf(String body) {
        return body.trim().startsWith("{") ? "application/json" : "application/x-www-form-urlencoded";
    }
}
