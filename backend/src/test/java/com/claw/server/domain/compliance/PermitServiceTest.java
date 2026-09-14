package com.claw.server.domain.compliance;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.PermitStatus;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PermitService} 单元测试（切片 3）：签发/吊销/有效性判定/任务绑定。
 */
@ExtendWith(MockitoExtension.class)
class PermitServiceTest {

    private static final Long ASSET_ID = 10L;
    private static final Long PERMIT_ID = 55L;
    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-09-14T12:00:00Z");

    @Mock
    private DroneAirspacePermitRepository permitRepository;

    @Mock
    private DroneMissionPermitBindingRepository bindingRepository;

    @Mock
    private AssetRepository assetRepository;

    @InjectMocks
    private PermitService service;

    private static Asset droneAsset() {
        return Asset.builder().id(ASSET_ID).assetType(AssetType.DRONE).assetNo("DRONE-001").build();
    }

    private static DroneAirspacePermit activePermit(String province) {
        return DroneAirspacePermit.builder().id(PERMIT_ID).assetId(ASSET_ID)
                .permitNo("SSCA-UAV-2026-0001").scopeProvince(province)
                .validFrom(FROM).validTo(TO).status(PermitStatus.ACTIVE).build();
    }

    // ------------------------------------------------------------------ 签发

    @Test
    void issue_createsActivePermitWithDefaults() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(droneAsset()));
        when(permitRepository.existsByPermitNo("SSCA-UAV-2026-0001")).thenReturn(false);
        when(permitRepository.save(any(DroneAirspacePermit.class))).thenAnswer(inv -> inv.getArgument(0));

        DroneAirspacePermit saved = service.issue(ASSET_ID, "SSCA-UAV-2026-0001", null,
                "siem_reap", FROM, TO, "DOC-1");

        assertEquals(PermitStatus.ACTIVE, saved.getStatus());
        assertEquals("SSCA", saved.getIssuer(), "签发机构缺省 SSCA");
        assertEquals("siem_reap", saved.getScopeProvince());
        assertEquals("DOC-1", saved.getDocRef());
    }

    @Test
    void issue_rejectsBlankPermitNo() {
        BizException ex = assertThrows(BizException.class,
                () -> service.issue(ASSET_ID, "  ", null, null, FROM, TO, null));

        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        assertEquals("error.drone.permit.no.invalid", ex.getMessageCode());
        verify(permitRepository, never()).save(any());
    }

    @Test
    void issue_rejectsInvalidWindow() {
        BizException ex = assertThrows(BizException.class,
                () -> service.issue(ASSET_ID, "P-1", null, null, TO, FROM, null));

        assertEquals("error.drone.permit.window.invalid", ex.getMessageCode());
        verify(permitRepository, never()).save(any());
    }

    @Test
    void issue_rejectsMissingAssetWith404() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class,
                () -> service.issue(ASSET_ID, "P-1", null, null, FROM, TO, null));

        assertEquals(40466, ex.getCode());
        verify(permitRepository, never()).save(any());
    }

    @Test
    void issue_rejectsDuplicatePermitNoWith409() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(droneAsset()));
        when(permitRepository.existsByPermitNo("P-1")).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> service.issue(ASSET_ID, "P-1", null, null, FROM, TO, null));

        assertEquals(40968, ex.getCode());
        assertEquals("error.drone.permit.duplicate", ex.getMessageCode());
        verify(permitRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ 吊销

    @Test
    void revoke_setsRevokedAndKeepsHistory() {
        when(permitRepository.findById(PERMIT_ID)).thenReturn(Optional.of(activePermit(null)));
        when(permitRepository.save(any(DroneAirspacePermit.class))).thenAnswer(inv -> inv.getArgument(0));

        DroneAirspacePermit saved = service.revoke(PERMIT_ID);

        assertEquals(PermitStatus.REVOKED, saved.getStatus());
    }

    @Test
    void revoke_rejectsAlreadyRevokedWith409() {
        DroneAirspacePermit revoked = activePermit(null);
        revoked.setStatus(PermitStatus.REVOKED);
        when(permitRepository.findById(PERMIT_ID)).thenReturn(Optional.of(revoked));

        BizException ex = assertThrows(BizException.class, () -> service.revoke(PERMIT_ID));

        assertEquals(40969, ex.getCode());
        assertEquals("error.drone.permit.status.invalid", ex.getMessageCode());
    }

    @Test
    void revoke_rejectsMissingPermitWith404() {
        when(permitRepository.findById(PERMIT_ID)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.revoke(PERMIT_ID));

        assertEquals(40472, ex.getCode());
        assertEquals("error.drone.permit.not.found", ex.getMessageCode());
    }

    // ---------------------------------------------------------- 有效性判定

    @Test
    void validPermitFor_matchesActivePermitInWindow() {
        when(permitRepository.findByAssetIdAndStatusAndDeletedFalse(ASSET_ID, PermitStatus.ACTIVE))
                .thenReturn(List.of(activePermit(null)));

        assertTrue(service.validPermitFor(ASSET_ID, null, AT).isPresent());
    }

    @Test
    void validPermitFor_rejectsExpiredPermit() {
        // 许可 9-01 起两天后即过期，判定时刻取 9-14。
        DroneAirspacePermit expired = DroneAirspacePermit.builder().id(PERMIT_ID).assetId(ASSET_ID)
                .permitNo("P-EXPIRED").validFrom(FROM).validTo(FROM.plusSeconds(172800))
                .status(PermitStatus.ACTIVE).build();
        when(permitRepository.findByAssetIdAndStatusAndDeletedFalse(ASSET_ID, PermitStatus.ACTIVE))
                .thenReturn(List.of(expired));

        assertTrue(service.validPermitFor(ASSET_ID, null, AT).isEmpty(), "时间窗未覆盖判定时刻 → 无有效许可");
    }

    @Test
    void validPermitFor_rejectsProvinceMismatch() {
        when(permitRepository.findByAssetIdAndStatusAndDeletedFalse(ASSET_ID, PermitStatus.ACTIVE))
                .thenReturn(List.of(activePermit("SIEM_REAP")));

        assertTrue(service.validPermitFor(ASSET_ID, "BATTAMBANG", AT).isEmpty(), "省域不覆盖 → 无有效许可");
    }

    @Test
    void validPermitFor_acceptsUnscopedPermitForAnyProvince() {
        when(permitRepository.findByAssetIdAndStatusAndDeletedFalse(ASSET_ID, PermitStatus.ACTIVE))
                .thenReturn(List.of(activePermit(null)));

        assertTrue(service.validPermitFor(ASSET_ID, "BATTAMBANG", AT).isPresent(),
                "许可不限省域 → 任意省域均覆盖");
    }

    @Test
    void validPermitFor_emptyWithoutAsset() {
        assertTrue(service.validPermitFor(null, null, AT).isEmpty());
    }

    // ---------------------------------------------------------- 任务绑定

    @Test
    void bindMission_createsBinding() {
        when(permitRepository.findById(PERMIT_ID)).thenReturn(Optional.of(activePermit(null)));
        when(bindingRepository.save(any(DroneMissionPermitBinding.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DroneMissionPermitBinding saved = service.bindMission(PERMIT_ID, 901L, null);

        assertEquals(PERMIT_ID, saved.getPermitId());
        assertEquals(901L, saved.getTaskId());
    }

    @Test
    void bindMission_rejectsMissingPermitWith404() {
        when(permitRepository.findById(PERMIT_ID)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class,
                () -> service.bindMission(PERMIT_ID, 901L, null));

        assertEquals(40472, ex.getCode());
    }

    @Test
    void bindMission_rejectsBothReferencesBlank() {
        when(permitRepository.findById(PERMIT_ID)).thenReturn(Optional.of(activePermit(null)));

        BizException ex = assertThrows(BizException.class,
                () -> service.bindMission(PERMIT_ID, null, null));

        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        assertEquals("error.drone.mission.permit.binding.invalid", ex.getMessageCode());
    }

    @Test
    void list_filtersByAssetWhenProvided() {
        when(permitRepository.findByAssetIdAndDeletedFalseOrderByCreatedAtDesc(ASSET_ID))
                .thenReturn(List.of(activePermit(null)));

        assertEquals(1, service.list(ASSET_ID).size());
    }
}
