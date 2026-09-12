package com.claw.server.domain.ocpp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * OCPP 1.6J 报文路由（纯函数，无 DB / 无 Spring 依赖，易单测）。
 *
 * <p>负责：
 * <ul>
 *   <li>唯一 {@code msgId} 生成（自增 + 纳秒时间戳，避免并发碰撞）；</li>
 *   <li>入站帧解析（CALL / CALLRESULT / CALLERROR 路由判别）；</li>
 *   <li>出站帧构造（CALL / CALLRESULT / CALLERROR JSON 数组）。</li>
 * </ul>
 *
 * <p>OCPP JSON 帧格式：
 * <pre>
 *   CALL        = [2, "msgId", "Action", {payload}]
 *   CALLRESULT  = [3, "msgId", {payload}]
 *   CALLERROR   = [4, "msgId", "errorCode", "description", {details}]
 * </pre>
 */
@Slf4j
public class OcppMessageRouter {

    /** OCPP 消息类型前缀。 */
    public static final int TYPE_CALL = 2;
    public static final int TYPE_CALLRESULT = 3;
    public static final int TYPE_CALLERROR = 4;

    /** 标准 OCPP 错误码（1.6 子集，够本期使用）。 */
    public static final String ERR_NOT_SUPPORTED = "NotSupported";
    public static final String ERR_NOT_IMPLEMENTED = "NotImplemented";
    public static final String ERR_INTERNAL = "InternalError";
    public static final String ERR_FORMAT_VIOLATION = "FormatException";
    public static final String ERR_SECURITY = "SecurityError";
    public static final String ERR_PROPERTY_CONSTRAINT = "PropertyConstraintViolation";

    private final ObjectMapper objectMapper;
    private final AtomicLong counter = new AtomicLong(0);

    public OcppMessageRouter() {
        this.objectMapper = new ObjectMapper();
    }

    public OcppMessageRouter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /** 生成全局唯一 msgId（服务端发起 CALL 用）。 */
    public String nextMessageId() {
        return "cmd-" + System.nanoTime() + "-" + counter.incrementAndGet();
    }

    /** 入站帧解析结果。 */
    public record ParsedMessage(
            int type,
            String messageId,
            String action,        // CALL 才有
            JsonNode payload,    // CALL / CALLRESULT 的 payload；CALLERROR 为 details
            String errorCode,    // CALLERROR 才有
            String errorDesc,    // CALLERROR 才有
            String raw) {

        public boolean isCall() {
            return type == TYPE_CALL;
        }

        public boolean isCallResult() {
            return type == TYPE_CALLRESULT;
        }

        public boolean isCallError() {
            return type == TYPE_CALLERROR;
        }
    }

    /**
     * 解析入站 JSON 帧。解析失败（非数组 / 长度不足）返回 type=0 的无效结果，调用方据此回 CALLERROR。
     */
    public ParsedMessage parse(String json) {
        if (json == null || json.isBlank()) {
            return new ParsedMessage(0, null, null, null, null, null, json);
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray() || root.size() < 3) {
                return new ParsedMessage(0, null, null, null, null, null, json);
            }
            int type = root.get(0).asInt();
            String messageId = root.get(1).asText();
            return switch (type) {
                case TYPE_CALL -> {
                    String action = root.size() >= 3 ? root.get(2).asText() : "";
                    JsonNode payload = root.size() >= 4 ? root.get(3) : objectMapper.createObjectNode();
                    yield new ParsedMessage(type, messageId, action, payload, null, null, json);
                }
                case TYPE_CALLRESULT -> {
                    JsonNode payload = root.get(2);
                    yield new ParsedMessage(type, messageId, null, payload, null, null, json);
                }
                case TYPE_CALLERROR -> {
                    String errorCode = root.size() >= 3 ? root.get(2).asText() : ERR_INTERNAL;
                    String desc = root.size() >= 4 ? root.get(3).asText() : "";
                    JsonNode details = root.size() >= 5 ? root.get(4) : objectMapper.createObjectNode();
                    yield new ParsedMessage(type, messageId, null, details, errorCode, desc, json);
                }
                default -> new ParsedMessage(0, messageId, null, null, null, null, json);
            };
        } catch (Exception e) {
            log.warn("[OCPP] 报文解析失败：{}", e.getMessage());
            return new ParsedMessage(0, null, null, null, null, null, json);
        }
    }

    /** 构造 CALL 帧（服务端 → 桩）。 */
    public String buildCall(String msgId, String action, Object payload) {
        ArrayNode arr = objectMapper.createArrayNode();
        arr.add(TYPE_CALL);
        arr.add(msgId);
        arr.add(action);
        arr.add(objectMapper.valueToTree(payload != null ? payload : objectMapper.createObjectNode()));
        return write(arr);
    }

    /** 构造 CALLRESULT 帧（应答桩的 CALL）。 */
    public String buildCallResult(String msgId, Object payload) {
        ArrayNode arr = objectMapper.createArrayNode();
        arr.add(TYPE_CALLRESULT);
        arr.add(msgId);
        arr.add(objectMapper.valueToTree(payload != null ? payload : objectMapper.createObjectNode()));
        return write(arr);
    }

    /** 构造 CALLERROR 帧（应答桩的 CALL，拒绝/异常）。 */
    public String buildCallError(String msgId, String errorCode, String description) {
        return buildCallError(msgId, errorCode, description, objectMapper.createObjectNode());
    }

    /** 构造 CALLERROR 帧（带 details）。 */
    public String buildCallError(String msgId, String errorCode, String description, Object details) {
        ArrayNode arr = objectMapper.createArrayNode();
        arr.add(TYPE_CALLERROR);
        arr.add(msgId);
        arr.add(errorCode != null ? errorCode : ERR_INTERNAL);
        arr.add(description != null ? description : "");
        arr.add(objectMapper.valueToTree(details != null ? details : objectMapper.createObjectNode()));
        return write(arr);
    }

    /** 取 payload 里的字符串字段（解析兼容）。 */
    public static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private String write(ArrayNode arr) {
        try {
            return objectMapper.writeValueAsString(arr);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OCPP 帧序列化失败", e);
        }
    }

    /** 便于测试：构造一个空的 CALLRESULT payload 节点。 */
    public ObjectNode emptyPayload() {
        return objectMapper.createObjectNode();
    }
}
