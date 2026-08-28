package com.claw.server.domain.asset;

import com.claw.server.common.dto.AssetRequests.ProvisionAssetReq;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 独立核验（QA 严过关）：provisionFromRegistration 对三种资产类型都应
 * ① 把 assets.owner_id 设为买家（req.ownerId），② 调用 grantOwnership
 * （OWNER 角色 + MANAGE ACL）。工程师自测未覆盖 BATTERY 路径。
 */
@ExtendWith(MockitoExtension.class)
class ProvisionOwnershipAuditTest {

    @Mock AssetRepository assetRepository;
    @Mock VehicleRepository vehicleRepository;
    @Mock BatteryRepository batteryRepository;
    @Mock AssetStatusLogRepository statusLogRepository;
    @Mock AssetLifecycleEventRepository lifecycleEventRepository;
    @Mock UserAssetsAclRepository aclRepository;
    @Mock PermissionService permissionService;
    @Mock RoleGrantService roleGrantService;
    @Mock DataScopeService dataScopeService;
    @Mock UserRepository userRepository;
    @Mock CustodyService custodyService;
    @Mock DeviceRepository deviceRepository;
    @Mock DroneRepository droneRepository;
    @Mock TelemetryRepository telemetryRepository;
    @Mock AssetMaintenanceRecordRepository maintenanceRecordRepository;

    @InjectMocks AssetService assetService;

    private static final Long BUYER = 5L;
    private static final Long OPERATOR = 99L;

    private void stubSaveAndFind(AssetType type, Long ownerId) {
        when(assetRepository.findByAssetNo(anyString())).thenReturn(Optional.empty());
        when(assetRepository.save(any(Asset.class))).thenAnswer(inv -> {
            Asset a = (Asset) inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(100L);
            }
            return a;
        });
        Asset persisted = Asset.builder().assetType(type).build();
        persisted.setId(100L);
        persisted.setOwnerId(ownerId);
        when(assetRepository.findById(100L)).thenReturn(Optional.of(persisted));
    }

    @Test
    void vehicle_provision_sets_buyer_owner_and_grants_ownership() {
        stubSaveAndFind(AssetType.VEHICLE, BUYER);
        ProvisionAssetReq req = new ProvisionAssetReq("VEHICLE", null, "QR-V", 2L, 5L, 3L,
                "SN", BUYER, 10L, "V1", "F1", "M1", null, "M-X",
                BigDecimal.ZERO, "[{\"type\":\"BATTERY\",\"no\":\"B1\"}]");
        assetService.provisionFromRegistration(req, OPERATOR);

        ArgumentCaptor<Asset> cap = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository, atLeastOnce()).save(cap.capture());
        Asset saved = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertEquals(BUYER, saved.getOwnerId(), "VEHICLE 资产 ownerId 应为买家");
        verify(roleGrantService).grantByEvent(eq(BUYER), eq("OWNER"));
    }

    @Test
    void battery_provision_must_set_buyer_owner_and_grant_ownership() {
        stubSaveAndFind(AssetType.BATTERY, OPERATOR); // createBattery 实际写的是 operatorId
        ProvisionAssetReq req = new ProvisionAssetReq("BATTERY", null, "QR-B", 2L, 5L, 3L,
                "SN", BUYER, 10L, null, null, null, null, "M-X",
                BigDecimal.ZERO, "[{\"type\":\"BATTERY\",\"no\":\"B1\"}]");
        assetService.provisionFromRegistration(req, OPERATOR);

        ArgumentCaptor<Asset> cap = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository, atLeastOnce()).save(cap.capture());
        Asset saved = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertEquals(BUYER, saved.getOwnerId(), "BATTERY 资产 ownerId 应为买家（当前实现写成 operatorId）");
        verify(roleGrantService).grantByEvent(eq(BUYER), eq("OWNER"));
    }
}
