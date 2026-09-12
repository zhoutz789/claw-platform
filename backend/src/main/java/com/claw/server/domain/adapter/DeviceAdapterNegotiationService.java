package com.claw.server.domain.adapter;

import com.claw.server.common.api.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * 设备协议自动协商服务（接入半边业务层）。
 *
 * <ul>
 *   <li>{@link #detectProtocol(Map)}：从握手元数据识别/推断协议；</li>
 *   <li>{@link #bindDevice(Long, Map)}：绑定设备到协议 profile（幂等）；</li>
 *   <li>{@link #normalizeAndIngest(Long, String)}：按绑定解析契约并归一化原始报文。</li>
 * </ul>
 *
 * <p>复用 BmsAdapter / OcppAdapter 的「多 profile 归一化」思路，将 OCPP/BMS/vehicle 收敛到统一抽象。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceAdapterNegotiationService {

    /** 默认协议：握手元数据无法识别时的回退（报文即规范 JSON）。 */
    public static final String GENERIC_MQTT = "GENERIC_MQTT";

    private final AdapterProfileRepository adapterProfileRepository;
    private final DeviceAdapterRepository deviceAdapterRepository;
    private final DeviceAdapterRegistry registry;

    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从握手元数据识别协议：
     * <ol>
     *   <li>优先取 handshakeMeta["protocol"]；</li>
     *   <li>否则按 vendor 关键字朴素推断（如 ocpp→OCPP、pylon→PYLON_CAN）；</li>
     *   <li>均无法识别则回退 {@link #GENERIC_MQTT}。</li>
     * </ol>
     */
    public String detectProtocol(Map<String, Object> handshakeMeta) {
        if (handshakeMeta == null) {
            return GENERIC_MQTT;
        }
        Object protocol = handshakeMeta.get("protocol");
        if (protocol != null && !protocol.toString().isBlank()) {
            return protocol.toString().trim();
        }
        Object vendor = handshakeMeta.get("vendor");
        if (vendor != null) {
            String v = vendor.toString().toLowerCase();
            if (v.contains("ocpp")) {
                return "OCPP";
            }
            if (v.contains("pylon")) {
                return "PYLON_CAN";
            }
        }
        return GENERIC_MQTT;
    }

    /**
     * 绑定设备到协议 profile（幂等）。
     *
     * <p>流程：detectProtocol → 解析 AdapterProfile → 若 (deviceId, profileId) 已存在则复用，否则新建。
     */
    @Transactional
    public DeviceAdapter bindDevice(Long deviceId, Map<String, Object> handshakeMeta) {
        String protocol = detectProtocol(handshakeMeta);
        AdapterProfile profile = adapterProfileRepository.findByProtocol(protocol)
                .orElseThrow(() -> BizException.notFound("error.adapter.profile.not.found"));
        return deviceAdapterRepository.findByDeviceIdAndProfileId(deviceId, profile.getId())
                .orElseGet(() -> {
                    DeviceAdapter binding = DeviceAdapter.builder()
                            .deviceId(deviceId)
                            .profileId(profile.getId())
                            .negotiatedMetaJson(toJson(handshakeMeta))
                            .createdAt(Instant.now())
                            .build();
                    log.info("[AdapterNegotiation] 设备 {} 绑定协议 {} (profileId={})",
                            deviceId, protocol, profile.getId());
                    return deviceAdapterRepository.save(binding);
                });
    }

    /**
     * 按设备绑定解析契约并归一化原始报文。
     *
     * <p>注：当前未提供 TelemetryService.save(...)（仓储层仅有 TelemetryRepository），
     * 归一化结果仅作为 {@link TelemetryView} 返回，不落库（不新增遥测表）；
     * 如需落库可在此接入 TelemetryRepository。
     */
    @Transactional(readOnly = true)
    public TelemetryView normalizeAndIngest(Long deviceId, String rawJson) {
        DeviceAdapter binding = deviceAdapterRepository.findByDeviceId(deviceId).stream()
                .findFirst()
                .orElseThrow(() -> BizException.notFound("error.adapter.device.binding.not.found"));
        AdapterProfile profile = adapterProfileRepository.findById(binding.getProfileId())
                .orElseThrow(() -> BizException.notFound("error.adapter.profile.not.found"));
        DeviceAdapterContract contract = registry.resolve(profile.getProtocol());
        return contract.normalizeTelemetry(rawJson);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("[AdapterNegotiation] 握手元数据序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
