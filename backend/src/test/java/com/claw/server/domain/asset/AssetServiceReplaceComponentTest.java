package com.claw.server.domain.asset;

import com.claw.server.common.dto.AssetRequests.BindDevice;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.domain.custody.CustodyService;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.iot.TelemetryRepository;
import com.claw.server.domain.role.DataScopeService;
import com.claw.server.domain.role.PermissionService;
import com.claw.server.domain.role.RoleGrantService;
import com.claw.server.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AssetService.replaceComponent 单测（Mockito，无真实数据库）。
 * 覆盖：① 已存在部件类型 → 更新快照 + 写维修记录；② 新部件类型 → 追加快照 + 写维修记录。
 */
@ExtendWith(MockitoExtension.class)
class AssetServiceReplaceComponentTest {

    @Mock private AssetRepository assetRepository;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private BatteryRepository batteryRepository;
    @Mock private AssetStatusLogRepository statusLogRepository;
    @Mock private AssetLifecycleEventRepository lifecycleEventRepository;
    @Mock private UserAssetsAclRepository aclRepository;
    @Mock private PermissionService permissionService;
    @Mock private RoleGrantService roleGrantService;
    @Mock private DataScopeService dataScopeService;
    @Mock private UserRepository userRepository;
    @Mock private CustodyService custodyService;
    @Mock private DeviceRepository deviceRepository;
    @Mock private DroneRepository droneRepository;
    @Mock private TelemetryRepository telemetryRepository;
    @Mock private AssetMaintenanceRecordRepository maintenanceRecordRepository;

    @InjectMocks private AssetService assetService;

    private Asset baseAsset(Long id, Long ownerId, String componentNosJson) {
        Asset a = Asset.builder()
                .assetType(AssetType.VEHICLE)
                .assetNo("ASSET-" + id)
                .qrCode("QR-" + id)
                .ownerId(ownerId)
                .status(AssetStatus.IN_STOCK)
                .build();
        a.setId(id);
        a.setComponentNosJson(componentNosJson);
        return a;
    }

    @Test
    void replaceComponent_updates_existing_snapshot_and_writes_record() {
        Long assetId = 7L;
        Long ownerId = 5L;
        Asset a = baseAsset(assetId, ownerId, "[{\"type\":\"MOTOR\",\"no\":\"M-OLD\"}]");
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(a));
        // requireManage 由 ownerId 匹配 operatorId 短路通过
        when(assetRepository.save(any(Asset.class))).thenAnswer(inv -> inv.getArgument(0));
        when(maintenanceRecordRepository.save(any(AssetMaintenanceRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assetService.replaceComponent(assetId, "MOTOR", "M-OLD", "M-NEW", ownerId);

        // ① 快照更新：M-OLD 被替换为 M-NEW
        ArgumentCaptor<Asset> assetCap = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(assetCap.capture());
        String json = assetCap.getValue().getComponentNosJson();
        assertTrue(json.contains("M-NEW"), "快照应含新编号 " + json);
        assertFalse(json.contains("M-OLD"), "快照不应再含旧编号 " + json);

        // ② 维修记录写入：COMPONENT_REPLACE + 旧/新编号 + 类型
        ArgumentCaptor<AssetMaintenanceRecord> recCap =
                ArgumentCaptor.forClass(AssetMaintenanceRecord.class);
        verify(maintenanceRecordRepository).save(recCap.capture());
        AssetMaintenanceRecord rec = recCap.getValue();
        assertEquals("COMPONENT_REPLACE", rec.getMtype());
        assertEquals("MOTOR", rec.getComponentType());
        assertEquals("M-OLD", rec.getOldComponentNo());
        assertEquals("M-NEW", rec.getNewComponentNo());
        assertEquals(assetId, rec.getAssetId());
    }

    @Test
    void replaceComponent_appends_new_component_type() {
        Long assetId = 8L;
        Long ownerId = 5L;
        Asset a = baseAsset(assetId, ownerId, "[{\"type\":\"BATTERY\",\"no\":\"B1\"}]");
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(a));
        when(assetRepository.save(any(Asset.class))).thenAnswer(inv -> inv.getArgument(0));
        when(maintenanceRecordRepository.save(any(AssetMaintenanceRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assetService.replaceComponent(assetId, "CONTROLLER", null, "C-NEW", ownerId);

        ArgumentCaptor<Asset> assetCap = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(assetCap.capture());
        String json = assetCap.getValue().getComponentNosJson();
        assertTrue(json.contains("BATTERY"), "既有部件应保留 " + json);
        assertTrue(json.contains("CONTROLLER"), "新部件应追加 " + json);
        assertTrue(json.contains("C-NEW"), "新编号应存在 " + json);

        ArgumentCaptor<AssetMaintenanceRecord> recCap =
                ArgumentCaptor.forClass(AssetMaintenanceRecord.class);
        verify(maintenanceRecordRepository).save(recCap.capture());
        assertEquals("CONTROLLER", recCap.getValue().getComponentType());
        assertEquals("C-NEW", recCap.getValue().getNewComponentNo());
    }

    /** replaceComponent 不触碰 bindDevice（独立性：该逻辑在 AssetProvisionedIntegration）。 */
    @Test
    void replaceComponent_does_not_invoke_bindDevice() {
        Long assetId = 9L;
        Long ownerId = 5L;
        Asset a = baseAsset(assetId, ownerId, "[]");
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(a));
        when(assetRepository.save(any(Asset.class))).thenAnswer(inv -> inv.getArgument(0));
        when(maintenanceRecordRepository.save(any(AssetMaintenanceRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assetService.replaceComponent(assetId, "REMOTE", null, "R-NEW", ownerId);

        verify(deviceRepository, never()).save(any());
    }
}
