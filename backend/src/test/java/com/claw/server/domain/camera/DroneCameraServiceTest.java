package com.claw.server.domain.camera;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.CameraStatus;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DroneCameraService} 单元测试（切片 3 · T09 摄像头接线）：
 * 复用 camera 子系统不建新表、CAMERA 设备挂载、幂等注册、非无人机资产拒绝。
 */
@ExtendWith(MockitoExtension.class)
class DroneCameraServiceTest {

    private static final Long ASSET_ID = 44L;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private CameraRepository cameraRepository;

    @InjectMocks
    private DroneCameraService service;

    private static Asset drone() {
        return Asset.builder().id(ASSET_ID).assetType(AssetType.DRONE).assetNo("DRONE-044").build();
    }

    private static Device cameraDevice() {
        return Device.builder().id(7L).assetId(ASSET_ID).deviceType("CAMERA")
                .deviceNo("DRONECAM-44-ABC").status("ACTIVE").build();
    }

    private static Camera cameraStream() {
        return Camera.builder().id(9L).assetId(ASSET_ID).cameraIdx(1).protocol("RTMP")
                .status(CameraStatus.WORKING).streamUrl("rtmp://edge/live/d44").build();
    }

    @Test
    void register_createsCameraDeviceAndStream() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(drone()));
        when(deviceRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        when(cameraRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> view = service.register(ASSET_ID, "rtmp://edge/live/d44", "rtsp");

        assertTrue((Boolean) view.get("created"));
        assertEquals("CAMERA", view.get("deviceType"));
        assertEquals("RTSP", view.get("protocol"), "流类型大小写归一");
        assertEquals("rtmp://edge/live/d44", view.get("streamUrl"));
        verify(deviceRepository).save(any(Device.class));
        verify(cameraRepository).save(any(Camera.class));
    }

    @Test
    void register_defaultsStreamTypeToRtmp() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(drone()));
        when(deviceRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        when(cameraRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> view = service.register(ASSET_ID, "rtmp://edge/live/d44", null);

        assertEquals("RTMP", view.get("protocol"));
    }

    @Test
    void register_isIdempotent_returnsExistingWithoutDuplicateRows() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(drone()));
        when(deviceRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(cameraDevice()));
        when(cameraRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(cameraStream()));

        Map<String, Object> view = service.register(ASSET_ID, "rtmp://other/live", "SRT");

        assertFalse((Boolean) view.get("created"), "重复注册返回既有，不报错也不重复建行");
        assertEquals(7L, view.get("deviceId"));
        assertEquals(9L, view.get("cameraId"));
        verify(deviceRepository, never()).save(any());
        verify(cameraRepository, never()).save(any());
    }

    @Test
    void register_rejectsNonDroneAssetWith409() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(
                Asset.builder().id(ASSET_ID).assetType(AssetType.VEHICLE).assetNo("V-1").build()));

        BizException ex = assertThrows(BizException.class,
                () -> service.register(ASSET_ID, "rtmp://edge/live", null));

        assertEquals(40970, ex.getCode());
        assertEquals("error.drone.camera.asset.not.drone", ex.getMessageCode());
        verify(deviceRepository, never()).save(any());
    }

    @Test
    void register_rejectsMissingAssetWith404() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class,
                () -> service.register(ASSET_ID, "rtmp://edge/live", null));

        assertEquals(40466, ex.getCode());
    }

    @Test
    void register_rejectsUnsupportedStreamType() {
        BizException ex = assertThrows(BizException.class,
                () -> service.register(ASSET_ID, "rtmp://edge/live", "HLS"));

        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        assertEquals("error.drone.camera.stream.type.invalid", ex.getMessageCode());
        verify(assetRepository, never()).findById(any());
    }

    @Test
    void primaryCamera_returnsFirstStream() {
        when(cameraRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(cameraStream()));

        assertEquals(9L, service.primaryCamera(ASSET_ID).getId());
    }

    @Test
    void primaryCamera_throws404WhenNotRegistered() {
        when(cameraRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class, () -> service.primaryCamera(ASSET_ID));

        assertEquals(40473, ex.getCode());
        assertEquals("error.drone.camera.not.found", ex.getMessageCode());
    }
}
