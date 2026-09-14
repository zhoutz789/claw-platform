package com.claw.server.domain.compliance;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.DroneCommandType;
import com.claw.server.common.enums.NfzLevel;
import com.claw.server.common.enums.PermitStatus;
import com.claw.server.domain.iot.DroneTrajectory;
import com.claw.server.domain.iot.DroneTrajectoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PermitGate} 单元测试（切片 3 核心）。
 *
 * <p>覆盖验收要求的全部档位语义：STRICT 拒发 / ADVISORY 提示放行 / OFF 跳过运营限制 /
 * <b>安全指令豁免</b> / 零容忍区恒拒（含 OFF、ADVISORY 档）/ 许可过期拒。
 * 其中「零容忍区在 ADVISORY 档也必须拒」是最容易写错的一条 —— 档位只能放开运营限制，
 * 不能放开航空安全底线，这里用两条独立用例钉死。
 */
@ExtendWith(MockitoExtension.class)
class PermitGateTest {

    private static final Long ASSET_ID = 10L;
    private static final Instant AT = Instant.parse("2026-09-14T12:00:00Z");

    @Mock
    private NfzService nfzService;

    @Mock
    private PermitService permitService;

    @Mock
    private DroneComplianceConfigService configService;

    @Mock
    private DroneTrajectoryRepository trajectoryRepository;

    @InjectMocks
    private PermitGate gate;

    private static DroneTrajectory pointAt(double lng, double lat) {
        return DroneTrajectory.builder().assetId(ASSET_ID).lng(lng).lat(lat).ts(AT).build();
    }

    private static NfzLayer bakedLayer() {
        return NfzLayer.builder().id(1L).name("Techo International Airport No-Fly Zone")
                .level(NfzLevel.ABSOLUTE).source(NfzLayer.SOURCE_BAKED_IN).enabled(true).build();
    }

    private static NfzLayer operationalLayer() {
        return NfzLayer.builder().id(4L).name("Thai-Cambodia Border Province Operation Restriction")
                .level(NfzLevel.TIME_WINDOW).source("REGULATION-2025").enabled(true).build();
    }

    private static DroneAirspacePermit permit() {
        return DroneAirspacePermit.builder().id(1L).assetId(ASSET_ID).permitNo("SSCA-UAV-2026-0001")
                .validFrom(AT.minusSeconds(3600)).validTo(AT.plusSeconds(3600))
                .status(PermitStatus.ACTIVE).build();
    }

    // ------------------------------------------------------------ ① 安全指令豁免

    @Test
    void safetyCommand_exempt_evenInStrictWithoutPermit() {
        // 安全指令在读取档位之前即放行，故连 permit_gate_mode 都不查。
        when(configService.regulatoryProfile()).thenReturn("KH-EARLY-OPERATION");

        PermitGate.Decision d = gate.check(ASSET_ID, DroneCommandType.LAND, null, AT);

        assertTrue(d.allowed(), "LAND 属安全指令，任何档位都不拦（空中安全优先）");
        assertFalse(d.hardDenied());
        assertEquals("SAFETY-EXEMPT", d.mode());
        verifyNoInteractions(nfzService, permitService);
        verify(trajectoryRepository, never()).findTopByAssetIdOrderByTsDesc(any());
    }

    @Test
    void safetyCommands_allFiveExempt() {
        when(configService.regulatoryProfile()).thenReturn("KH-GENERAL");

        for (DroneCommandType command : PermitGate.SAFETY_COMMANDS) {
            PermitGate.Decision d = gate.check(ASSET_ID, command, null, AT);
            assertTrue(d.allowed(), command + " 应被豁免");
        }
    }

    // ------------------------------------------------- ② 零容忍区：恒定拒绝

    @Test
    void zeroTolerance_denies_evenInOffMode() {
        when(configService.regulatoryProfile()).thenReturn("KH-EARLY-OPERATION");
        when(configService.permitGateMode()).thenReturn("OFF");
        when(trajectoryRepository.findTopByAssetIdOrderByTsDesc(ASSET_ID))
                .thenReturn(Optional.of(pointAt(103.87, 13.41)));
        when(nfzService.firstBakedInHit(103.87, 13.41, AT)).thenReturn(Optional.of(bakedLayer()));

        BizException ex = assertThrows(BizException.class,
                () -> gate.check(ASSET_ID, DroneCommandType.REMOTE_START, null, AT));

        assertEquals(40305, ex.getCode(), "零容忍区命中必须抛 COMPLIANCE_DENIED");
        assertEquals("error.drone.permit.denied", ex.getMessageCode());
    }

    @Test
    void zeroTolerance_denies_evenInAdvisoryMode() {
        when(configService.regulatoryProfile()).thenReturn("KH-EARLY-OPERATION");
        when(configService.permitGateMode()).thenReturn("ADVISORY");
        when(trajectoryRepository.findTopByAssetIdOrderByTsDesc(ASSET_ID))
                .thenReturn(Optional.of(pointAt(103.87, 13.41)));
        when(nfzService.firstBakedInHit(103.87, 13.41, AT)).thenReturn(Optional.of(bakedLayer()));

        BizException ex = assertThrows(BizException.class,
                () -> gate.check(ASSET_ID, DroneCommandType.REMOTE_START, null, AT));

        assertEquals(40305, ex.getCode(), "ADVISORY 只能放开运营限制，不能放开安全底线");
    }

    // ------------------------------------------------- ③ 档位：STRICT / ADVISORY / OFF

    @Test
    void strict_deniesRemoteStart_withoutPermit() {
        when(configService.regulatoryProfile()).thenReturn("KH-EARLY-OPERATION");
        when(configService.permitGateMode()).thenReturn("STRICT");
        when(trajectoryRepository.findTopByAssetIdOrderByTsDesc(ASSET_ID)).thenReturn(Optional.empty());
        when(configService.permitRequired()).thenReturn(true);
        when(permitService.validPermitFor(ASSET_ID, null, AT)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class,
                () -> gate.check(ASSET_ID, DroneCommandType.REMOTE_START, null, AT));

        assertEquals(40305, ex.getCode());
        verify(nfzService, never()).firstBakedInHit(anyDouble(), anyDouble(), any());
        verify(nfzService, never()).firstOperationalHit(anyDouble(), anyDouble(), any());
    }

    @Test
    void advisory_warnsAndAllows_withoutPermit() {
        when(configService.regulatoryProfile()).thenReturn("KH-EARLY-OPERATION");
        when(configService.permitGateMode()).thenReturn("ADVISORY");
        when(trajectoryRepository.findTopByAssetIdOrderByTsDesc(ASSET_ID)).thenReturn(Optional.empty());
        when(configService.permitRequired()).thenReturn(true);
        when(permitService.validPermitFor(ASSET_ID, null, AT)).thenReturn(Optional.empty());

        PermitGate.Decision d = gate.check(ASSET_ID, DroneCommandType.REMOTE_START, null, AT);

        assertTrue(d.allowed(), "ADVISORY 档：提示而非阻断");
        assertFalse(d.hardDenied());
        assertFalse(d.reasons().isEmpty(), "放行但须留下拒绝原因供审计");
    }

    @Test
    void off_skipsOperationalChecks_butStillEvaluatesBakedInZones() {
        when(configService.regulatoryProfile()).thenReturn("KH-EARLY-OPERATION");
        when(configService.permitGateMode()).thenReturn("OFF");
        when(trajectoryRepository.findTopByAssetIdOrderByTsDesc(ASSET_ID))
                .thenReturn(Optional.of(pointAt(104.92, 11.56)));
        when(nfzService.firstBakedInHit(104.92, 11.56, AT)).thenReturn(Optional.empty());

        PermitGate.Decision d = gate.check(ASSET_ID, DroneCommandType.REMOTE_START, null, AT);

        assertTrue(d.allowed());
        verify(nfzService, never()).firstOperationalHit(anyDouble(), anyDouble(), any());
        verifyNoInteractions(permitService);
    }

    // ------------------------------------------------- ④⑤ 合规档与许可

    @Test
    void generalProfile_doesNotRequirePermit() {
        when(configService.regulatoryProfile()).thenReturn("KH-GENERAL");
        when(configService.permitGateMode()).thenReturn("STRICT");
        when(trajectoryRepository.findTopByAssetIdOrderByTsDesc(ASSET_ID)).thenReturn(Optional.empty());

        PermitGate.Decision d = gate.check(ASSET_ID, DroneCommandType.REMOTE_START, null, AT);

        assertTrue(d.allowed(), "KH-GENERAL 档不强制许可，仅 NFZ");
        verify(permitService, never()).validPermitFor(any(), any(), any());
    }

    @Test
    void strict_withValidPermit_passesGate() {
        when(configService.regulatoryProfile()).thenReturn("KH-EARLY-OPERATION");
        when(configService.permitGateMode()).thenReturn("STRICT");
        when(trajectoryRepository.findTopByAssetIdOrderByTsDesc(ASSET_ID)).thenReturn(Optional.empty());
        when(configService.permitRequired()).thenReturn(true);
        when(permitService.validPermitFor(ASSET_ID, "SIEM_REAP", AT)).thenReturn(Optional.of(permit()));

        PermitGate.Decision d = gate.check(ASSET_ID, DroneCommandType.REMOTE_START, "SIEM_REAP", AT);

        assertTrue(d.allowed());
    }

    @Test
    void operationalNfzHit_deniesInStrict() {
        when(configService.regulatoryProfile()).thenReturn("KH-GENERAL");
        when(configService.permitGateMode()).thenReturn("STRICT");
        when(trajectoryRepository.findTopByAssetIdOrderByTsDesc(ASSET_ID))
                .thenReturn(Optional.of(pointAt(102.9, 13.0)));
        when(nfzService.firstBakedInHit(102.9, 13.0, AT)).thenReturn(Optional.empty());
        when(nfzService.firstOperationalHit(102.9, 13.0, AT)).thenReturn(Optional.of(operationalLayer()));

        BizException ex = assertThrows(BizException.class,
                () -> gate.check(ASSET_ID, DroneCommandType.REMOTE_START, null, AT));

        assertEquals(40305, ex.getCode());
        assertTrue(ex.getMessageCode().contains("permit.denied"));
    }

    @Test
    void noPosition_skipsSpatialChecks_butStillRequiresPermit() {
        when(configService.regulatoryProfile()).thenReturn("KH-EARLY-OPERATION");
        when(configService.permitGateMode()).thenReturn("STRICT");
        when(trajectoryRepository.findTopByAssetIdOrderByTsDesc(ASSET_ID)).thenReturn(Optional.empty());
        when(configService.permitRequired()).thenReturn(true);
        when(permitService.validPermitFor(ASSET_ID, null, AT)).thenReturn(Optional.of(permit()));

        PermitGate.Decision d = gate.check(ASSET_ID, DroneCommandType.REMOTE_START, null, AT);

        assertTrue(d.allowed());
        verify(nfzService, never()).firstBakedInHit(anyDouble(), anyDouble(), any());
        verify(nfzService, never()).firstOperationalHit(anyDouble(), anyDouble(), any());
    }
}
