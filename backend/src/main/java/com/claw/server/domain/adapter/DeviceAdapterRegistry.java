package com.claw.server.domain.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备协议适配器注册表（按协议名解析 {@link DeviceAdapterContract}）。
 *
 * <p>从 claw.adapter_profiles 读取 profile 配置（字段映射 / 下行模板），惰性构建并缓存契约实例。
 * 当前仅 {@link GenericJsonAdapter} 一种实现；新增物理协议时在此分支或注册新实现即可，
 * 收敛 OCPP/BMS/vehicle 等差异。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceAdapterRegistry {

    private final AdapterProfileRepository adapterProfileRepository;
    private final ObjectMapper objectMapper;

    private final Map<String, DeviceAdapterContract> cache = new ConcurrentHashMap<>();

    /** 按协议名解析适配器契约（缓存）。 */
    public DeviceAdapterContract resolve(String protocol) {
        return cache.computeIfAbsent(protocol, this::build);
    }

    private DeviceAdapterContract build(String protocol) {
        AdapterProfile profile = adapterProfileRepository.findByProtocol(protocol)
                .orElseThrow(() -> new IllegalStateException("未注册的协议 profile: " + protocol));
        return new GenericJsonAdapter(
                profile.getProtocol(),
                parseJsonMap(profile.getFieldMapJson()),
                parseJsonMap(profile.getDownlinkTemplatesJson())
        );
    }

    private Map<String, String> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("[AdapterRegistry] 解析 profile json 失败: {}", e.getMessage());
            return Map.of();
        }
    }
}
