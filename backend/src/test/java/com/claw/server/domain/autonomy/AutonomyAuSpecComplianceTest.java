package com.claw.server.domain.autonomy;

import com.claw.server.common.enums.DriveMode;
import com.claw.server.common.enums.GeofenceLevel;
import com.claw.server.common.enums.InteractionDirection;
import com.claw.server.common.enums.RevenueShareBasis;
import com.claw.server.common.enums.SafetyState;
import com.claw.server.domain.asset.VehicleCommandService;
import com.claw.server.domain.sharedpool.RevenueSplitRule;
import com.claw.server.domain.sharedpool.RevenueSplitRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 校验 AU2–AU7 规范方法是否齐备且按预期行为（薄层服务，Mock 仓储/下游）。
 */
@ExtendWith(MockitoExtension.class)
class AutonomyAuSpecComplianceTest {

    // ---- AU2 / AU6: module service ----
    @Mock
    private AutonomyModuleRepository moduleRepository;
    @Mock
    private VoiceInteractionRepository voiceInteractionRepository;
    @InjectMocks
    private AutonomyModuleService moduleService;

    // ---- AU3: task service ----
    @Mock
    private AutonomyTaskRepository taskRepository;
    @InjectMocks
    private AutonomyTaskService taskService;

    // ---- AU4: geofence service (需要 safety service mock) ----
    @Mock
    private GroundGeofenceRepository geofenceRepository;
    @Mock
    private AutonomySafetyService safetyService;
    @InjectMocks
    private AutonomyGeofenceService geofenceService;

    // ---- AU5: safety service (需要 module service + vehicle command mocks) ----
    @Mock
    private AutonomySafetyEventRepository eventRepository;
    @Mock
    private AutonomyModuleService moduleServiceDep;
    @Mock
    private VehicleCommandService vehicleCommandService;
    @InjectMocks
    private AutonomySafetyService safetyServiceReal;

    // ---- AU7: revenue service ----
    @Mock
    private RevenueSplitRuleRepository splitRuleRepository;
    @InjectMocks
    private AutonomyRevenueService revenueService;

    @Test
    void au2_createModuleIfAbsent_singleArg_usesDefaults() {
        when(moduleRepository.findAll()).thenReturn(List.of());
        when(moduleRepository.save(any(AutonomyModule.class))).thenAnswer(inv -> inv.getArgument(0));

        AutonomyModule m = moduleService.createModuleIfAbsent(7L);

        assertEquals(7L, m.getAssetId());
        assertEquals(DriveMode.ASSISTED, m.getDriveMode());
        assertEquals(SafetyState.NORMAL, m.getSafetyState());
        verify(moduleRepository).save(any(AutonomyModule.class));
    }

    @Test
    void au6_logVoice_persistsInteraction() {
        when(voiceInteractionRepository.save(any(VoiceInteraction.class))).thenAnswer(inv -> inv.getArgument(0));

        VoiceInteraction v = moduleService.logVoice(7L, InteractionDirection.IN, "turn.left", "en");

        assertEquals(7L, v.getAssetId());
        assertEquals(InteractionDirection.IN, v.getDirection());
        assertEquals("turn.left", v.getText());
        assertEquals("en", v.getLang());
    }

    @Test
    void au3_dispatch_setsPendingAndTaskId() {
        when(taskRepository.save(any(AutonomyTask.class))).thenAnswer(inv -> inv.getArgument(0));

        AutonomyTask t = taskService.dispatch(100L, 7L, "SWEEP", "[]");

        assertEquals(100L, t.getTaskId());
        assertEquals(7L, t.getAssetId());
        assertEquals("SWEEP", t.getSubtype());
        assertEquals("PENDING", t.getStatus());
        assertEquals(0, t.getProgressPct());
    }

    @Test
    void au3_reportProgress_updatesLatestTask() {
        AutonomyTask task = AutonomyTask.builder().id(5L).assetId(7L).status("PENDING").progressPct(0).build();
        when(taskRepository.findByAssetId(7L)).thenReturn(List.of(task));
        when(taskRepository.save(any(AutonomyTask.class))).thenAnswer(inv -> inv.getArgument(0));

        AutonomyTask updated = taskService.reportProgress(7L, 75);
        assertEquals(75, updated.getProgressPct());

        AutonomyTask done = taskService.reportProgress(7L, 100);
        assertEquals(100, done.getProgressPct());
        assertEquals("COMPLETED", done.getStatus());
    }

    @Test
    void au4_validatePathInGeofence_passesInsideWork() {
        GroundGeofence work = GroundGeofence.builder()
                .id(1L).name("work").level(GeofenceLevel.WORK)
                .polygonJson("[[103.9,10.9],[104.1,10.9],[104.1,11.1],[103.9,11.1],[103.9,10.9]]").build();
        when(geofenceRepository.findAll()).thenReturn(List.of(work));

        assertTrue(geofenceService.validatePathInGeofence(7L, "[[104.0,11.0],[104.05,11.05]]"));
        verify(safetyService, never()).triggerSafetyEvent(any(), any(), any(), any());
    }

    @Test
    void au4_validatePathInGeofence_triggersSafetyEventWhenOutOfBounds() {
        GroundGeofence work = GroundGeofence.builder()
                .id(1L).name("work").level(GeofenceLevel.WORK)
                .polygonJson("[[103.9,10.9],[104.1,10.9],[104.1,11.1],[103.9,11.1],[103.9,10.9]]").build();
        when(geofenceRepository.findAll()).thenReturn(List.of(work));
        when(safetyService.triggerSafetyEvent(any(), any(), any(), any())).thenReturn(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> geofenceService.validatePathInGeofence(7L, "[[105.0,11.0]]"));
        assertTrue(ex.getMessage().contains("geofence.path.out.of.bounds"));
        verify(safetyService).triggerSafetyEvent(eq(7L), eq("GEOFENCE"), eq("CRITICAL"), any());
    }

    @Test
    void au5_triggerSafetyEventAndLockForSafety() {
        when(eventRepository.save(any(AutonomySafetyEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        safetyServiceReal.triggerSafetyEvent(7L, "LOW_BATTERY", "CRITICAL", "{}");
        verify(moduleServiceDep).setSafetyState(eq(7L), eq(SafetyState.LOCKED));

        safetyServiceReal.lockForSafety(7L);
        verify(moduleServiceDep, times(2)).setSafetyState(eq(7L), eq(SafetyState.LOCKED));
        verify(vehicleCommandService).lock(7L);
    }

    @Test
    void au7_computeAutonomyRevenueSplit_usesRuleOrDefault() {
        when(splitRuleRepository.findFirstByAssetIdAndStatusAndDeletedFalseOrderByCreatedAtDesc(anyLong(), eq("ACTIVE")))
                .thenReturn(Optional.empty());
        AutonomyRevenueSplit def = revenueService.computeAutonomyRevenueSplit(7L, new BigDecimal("100"));
        assertEquals(new BigDecimal("60.00"), def.ownerShare());
        assertEquals(new BigDecimal("30.00"), def.platformAlgorithmShare());
        assertEquals(new BigDecimal("10.00"), def.providerShare());

        RevenueSplitRule rule = RevenueSplitRule.builder()
                .id(1L).assetId(7L)
                .ownerRate(new BigDecimal("0.70"))
                .stationRate(new BigDecimal("0.15"))
                .platformRate(new BigDecimal("0.10"))
                .insuranceRate(new BigDecimal("0.05"))
                .shareBasis(RevenueShareBasis.PER_SWAP)
                .status("ACTIVE").build();
        when(splitRuleRepository.findFirstByAssetIdAndStatusAndDeletedFalseOrderByCreatedAtDesc(anyLong(), eq("ACTIVE")))
                .thenReturn(Optional.of(rule));
        AutonomyRevenueSplit withRule = revenueService.computeAutonomyRevenueSplit(7L, new BigDecimal("100"));
        assertEquals(new BigDecimal("70.00"), withRule.ownerShare());
        assertEquals(new BigDecimal("10.00"), withRule.platformAlgorithmShare());
        assertEquals(new BigDecimal("20.00"), withRule.providerShare());
    }
}
