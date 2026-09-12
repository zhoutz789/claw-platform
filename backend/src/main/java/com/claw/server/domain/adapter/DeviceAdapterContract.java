package com.claw.server.domain.adapter;

import java.util.Map;

/**
 * 设备协议适配器契约（统一抽象 OCPP / BMS / vehicle 等不同设备协议）。
 *
 * <p>每个协议 profile 对应一个实现；{@link DeviceAdapterRegistry} 按协议名解析出契约实例。
 * 下行/上行均经此契约归一化，使接入层无需关心具体物理协议。
 */
public interface DeviceAdapterContract {

    /** 协议标识（对应 claw.adapter_profiles.protocol）。 */
    String getProtocol();

    /**
     * 把设备原始报文（JSON 字符串）归一化为 {@link TelemetryView}。
     *
     * @param rawJson 设备上报的原始报文
     * @return 协议无关的归一化遥测视图
     */
    TelemetryView normalizeTelemetry(String rawJson);

    /**
     * 渲染下行指令报文（按 profile 的 downlink_templates_json 模板 + 参数占位替换）。
     *
     * @param commandType 指令类型（模板键）
     * @param params      指令参数（替换 ${key} 占位）
     * @return 下行报文（字符串，JSON 或模板渲染结果）
     */
    String buildDownlink(String commandType, Map<String, Object> params);
}
