package com.claw.server.domain.camera;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.AssetType;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.iot.MqttSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 无人机摄像头接线服务（切片 3 · T09）：为无人机资产挂载 CAMERA 设备并注册 {@code camera_stream}。
 *
 * <p><b>不建新表</b>：完全复用摄像头子系统（{@link Camera} / {@code camera_stream}）与既有
 * 设备抽象（{@link Device} / {@code devices, deviceType=CAMERA}），无人机与车辆在取流/回放
 * 链路上同构 —— 像素不出站，这里只登记索引与取流基址，视频流由边缘媒体节点承载。
 *
 * <p><b>幂等</b>：同一资产重复注册返回既有设备与流（{@code created=false}），不报错也不重复建行；
 * 设备号 {@code DRONECAM-<assetId>-<rand>} 全局唯一，secret 复用 {@link MqttSigner#newDeviceSecret()}。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DroneCameraService {

    private static final String CAMERA_DEVICE_TYPE = "CAMERA";
    /** 支持的推流协议（camera_stream.protocol 列宽 VARCHAR(16)）。 */
    private static final Set<String> SUPPORTED_STREAM_TYPES = Set.of("RTMP", "SRT", "RTSP");

    private final AssetRepository assetRepository;
    private final DeviceRepository deviceRepository;
    private final CameraRepository cameraRepository;

    /**
     * 为无人机资产注册摄像头（幂等）。
     *
     * @param assetId    无人机 asset_id（须为 DRONE 类型资产）
     * @param streamUrl  边缘媒体节点取流基址（HLS/WebRTC 在其上拼接）
     * @param streamType 推流协议 RTMP / SRT / RTSP（可空，默认 RTMP）
     * @return 注册结果视图：{created, assetId, deviceId, deviceNo, cameraId, protocol, streamUrl}
     * @throws BizException 40466 error.drone.not.found（资产不存在）
     * @throws BizException 40970 error.drone.camera.asset.not.drone（资产不是无人机）
     * @throws BizException 10001 error.drone.camera.stream.type.invalid（流类型不支持）
     */
    @Transactional
    public Map<String, Object> register(Long assetId, String streamUrl, String streamType) {
        if (assetId == null) {
            throw BizException.invalidParam("error.param.invalid", "assetId");
        }
        String protocol = normalizedStreamType(streamType);
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> BizException.of(40466, "error.drone.not.found", assetId));
        if (asset.getAssetType() != AssetType.DRONE) {
            throw BizException.of(40970, "error.drone.camera.asset.not.drone", asset.getAssetType().name());
        }

        // 幂等：已有 CAMERA 设备 + 已有流，则直接返回既有（不重复建行）。
        Optional<Device> existingDevice = deviceRepository.findByAssetId(assetId).stream()
                .filter(d -> CAMERA_DEVICE_TYPE.equals(d.getDeviceType()))
                .findFirst();
        List<Camera> existingCameras = cameraRepository.findByAssetId(assetId);
        if (existingDevice.isPresent() && !existingCameras.isEmpty()) {
            log.info("无人机 {} 摄像头已注册，返回既有（幂等）", assetId);
            return toView(false, assetId, existingDevice.get(), existingCameras.get(0));
        }

        Device device = existingDevice.orElseGet(() -> deviceRepository.save(Device.builder()
                .assetId(assetId)
                .deviceType(CAMERA_DEVICE_TYPE)
                .deviceNo("DRONECAM-" + assetId + "-" + MqttSigner.nonce())
                .secret(MqttSigner.newDeviceSecret())
                .status("ACTIVE")
                .build()));

        Camera camera = existingCameras.isEmpty()
                ? cameraRepository.save(Camera.builder()
                        .assetId(assetId)
                        .cameraIdx(existingCameras.size() + 1)
                        .name("Drone Camera #" + assetId)
                        .protocol(protocol)
                        .resolution("1080p")
                        .status(com.claw.server.common.enums.CameraStatus.WORKING)
                        .streamUrl(streamUrl)
                        .lastHeartbeat(Instant.now())
                        .build())
                : existingCameras.get(0);

        log.info("无人机 {} 注册摄像头 cameraId={} protocol={} created={}",
                assetId, camera.getId(), protocol, existingCameras.isEmpty());
        return toView(existingCameras.isEmpty(), assetId, device, camera);
    }

    /**
     * 无人机摄像头取流地址（透传 {@link CameraService#live}，无人机与车辆同构）。
     *
     * @param assetId 无人机 asset_id
     * @return live 视图（cameraId/protocol/playUrl/hlsUrl/webrtcUrl）
     * @throws BizException 40473 error.drone.camera.not.found（该无人机尚未注册摄像头）
     */
    @Transactional(readOnly = true)
    public Camera primaryCamera(Long assetId) {
        List<Camera> cameras = cameraRepository.findByAssetId(assetId);
        if (cameras.isEmpty()) {
            throw BizException.of(40473, "error.drone.camera.not.found", assetId);
        }
        return cameras.get(0);
    }

    /** 归一化并校验推流协议（空则默认 RTMP）。 */
    private String normalizedStreamType(String streamType) {
        String type = streamType == null || streamType.isBlank() ? "RTMP" : streamType.trim().toUpperCase();
        if (!SUPPORTED_STREAM_TYPES.contains(type)) {
            throw BizException.invalidParam("error.drone.camera.stream.type.invalid", streamType);
        }
        return type;
    }

    /** 注册结果视图。 */
    private Map<String, Object> toView(boolean created, Long assetId, Device device, Camera camera) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("created", created);
        view.put("assetId", assetId);
        view.put("deviceId", device.getId());
        view.put("deviceNo", device.getDeviceNo());
        view.put("deviceType", device.getDeviceType());
        view.put("cameraId", camera.getId());
        view.put("cameraIdx", camera.getCameraIdx());
        view.put("protocol", camera.getProtocol());
        view.put("streamUrl", camera.getStreamUrl());
        return view;
    }
}
