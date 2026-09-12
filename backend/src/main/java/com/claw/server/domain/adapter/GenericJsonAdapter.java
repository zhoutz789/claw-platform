package com.claw.server.domain.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 通用 JSON 适配器（朴素实现，无外部解析依赖）。
 *
 * <p>按 profile 的 field_map_json 做字段映射（规范字段名 → 原始报文 JSON 路径，支持点路径），
 * 按 downlink_templates_json 做 ${param} 占位替换。作为 GENERIC_MQTT 等「报文即规范 JSON」协议的实现，
 * 无需引入专用解析库；物理协议（CAN/RS485）由边缘网关固件完成归一化后再经 MQTT 上送。
 */
public class GenericJsonAdapter implements DeviceAdapterContract {

    private final String protocol;
    private final Map<String, String> fieldMap;
    private final Map<String, String> downlinkTemplates;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public GenericJsonAdapter(String protocol,
                              Map<String, String> fieldMap,
                              Map<String, String> downlinkTemplates) {
        this.protocol = protocol;
        this.fieldMap = fieldMap != null ? fieldMap : Map.of();
        this.downlinkTemplates = downlinkTemplates != null ? downlinkTemplates : Map.of();
    }

    @Override
    public String getProtocol() {
        return protocol;
    }

    @Override
    public TelemetryView normalizeTelemetry(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return new TelemetryView(null, null, null, null, null, null, null);
        }
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            return new TelemetryView(
                    longVal(root, lookup("assetId")),
                    decimal(root, lookup("lat")),
                    decimal(root, lookup("lng")),
                    decimal(root, lookup("speedKph")),
                    decimal(root, lookup("soc")),
                    decimal(root, lookup("soh")),
                    decimal(root, lookup("temp"))
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("报文归一化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String buildDownlink(String commandType, Map<String, Object> params) {
        String template = downlinkTemplates.get(commandType);
        if (template == null) {
            // 无模板：退化为最小 JSON 信封，便于设备端解析
            try {
                ObjectNode env = MAPPER.createObjectNode();
                env.put("command", commandType);
                env.set("params", params == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(params));
                return MAPPER.writeValueAsString(env);
            } catch (Exception e) {
                throw new IllegalStateException("下行报文构造失败: " + e.getMessage(), e);
            }
        }
        String rendered = template;
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                rendered = rendered.replace("${" + e.getKey() + "}",
                        e.getValue() == null ? "" : e.getValue().toString());
            }
        }
        return rendered;
    }

    /** 规范字段名 → 原始报文路径；无映射时默认同名字段。 */
    private String lookup(String canonical) {
        return fieldMap.getOrDefault(canonical, canonical);
    }

    private BigDecimal decimal(JsonNode root, String path) {
        JsonNode n = traverse(root, path);
        return (n != null && n.isNumber()) ? n.decimalValue() : null;
    }

    private Long longVal(JsonNode root, String path) {
        JsonNode n = traverse(root, path);
        return (n != null && n.isNumber()) ? n.asLong() : null;
    }

    /** 按点路径遍历 JSON 节点（如 location.lat）。 */
    private JsonNode traverse(JsonNode root, String path) {
        if (path == null || path.isBlank() || root == null) {
            return null;
        }
        JsonNode cur = root;
        for (String seg : path.split("\\.")) {
            if (cur == null || !cur.isObject()) {
                return null;
            }
            cur = cur.get(seg);
        }
        return cur;
    }
}
