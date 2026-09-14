package com.claw.server.domain.airspace;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.PilotBehaviorType;
import com.claw.server.common.enums.PilotPenaltyType;
import com.claw.server.common.enums.PilotStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PilotOpsService} 单元测试（切片 2 · V141）。
 *
 * <p>覆盖：建档/审核状态机（PENDING → ACTIVE，非法迁移 409）、执照弱引用预检（404 而非唯一约束 500）、
 * 行为事件只记录不处罚、处罚对档案状态的确定性副作用（WARN/FINE 仅扣分，SUSPEND/REVOKE 推进状态）。
 */
@ExtendWith(MockitoExtension.class)
class PilotOpsServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long APPROVER_ID = 1L;
    private static final Long PROFILE_ID = 55L;
    private static final String LICENSE_NO = "SSCA-UAV-0001";

    @Mock
    private PilotProfileRepository profileRepository;

    @Mock
    private PilotBehaviorEventRepository behaviorRepository;

    @Mock
    private PilotPenaltyRepository penaltyRepository;

    @Mock
    private PilotLicenseRepository licenseRepository;

    @InjectMocks
    private PilotOpsService service;

    private static PilotProfile profile(PilotStatus status, int creditScore) {
        return PilotProfile.builder()
                .id(PROFILE_ID)
                .userId(USER_ID)
                .licenseNo(LICENSE_NO)
                .kycLevel("BASIC")
                .status(status)
                .level(1)
                .creditScore(creditScore)
                .build();
    }

    // ------------------------------------------------------------------ 建档 / 审核

    @Test
    void submitProfile_createsPendingProfileWithInitialCredit() {
        when(profileRepository.existsByUserId(USER_ID)).thenReturn(false);
        when(licenseRepository.existsByLicenseNo(LICENSE_NO)).thenReturn(true);
        when(profileRepository.save(any(PilotProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        PilotProfile saved = service.submitProfile(USER_ID, LICENSE_NO, "standard");

        assertEquals(PilotStatus.PENDING, saved.getStatus());
        assertEquals(Integer.valueOf(100), saved.getCreditScore());
        assertEquals(Integer.valueOf(1), saved.getLevel());
        assertEquals("STANDARD", saved.getKycLevel());
        assertEquals(LICENSE_NO, saved.getLicenseNo());
    }

    @Test
    void submitProfile_allowsBlankLicenseWithoutLookup() {
        when(profileRepository.existsByUserId(USER_ID)).thenReturn(false);
        when(profileRepository.save(any(PilotProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        PilotProfile saved = service.submitProfile(USER_ID, "  ", null);

        assertNull(saved.getLicenseNo());
        assertEquals("BASIC", saved.getKycLevel());
        verify(licenseRepository, never()).existsByLicenseNo(any());
    }

    @Test
    void submitProfile_rejectsDuplicateWith409() {
        when(profileRepository.existsByUserId(USER_ID)).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> service.submitProfile(USER_ID, LICENSE_NO, "BASIC"));

        assertEquals(40964, ex.getCode());
        assertEquals("error.pilot.profile.duplicate", ex.getMessageCode());
        verify(profileRepository, never()).save(any());
    }

    @Test
    void submitProfile_rejectsUnknownLicenseWith404() {
        when(profileRepository.existsByUserId(USER_ID)).thenReturn(false);
        when(licenseRepository.existsByLicenseNo(LICENSE_NO)).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> service.submitProfile(USER_ID, LICENSE_NO, "BASIC"));

        assertEquals(40469, ex.getCode());
        assertEquals("error.pilot.license.not.found", ex.getMessageCode());
        verify(profileRepository, never()).save(any());
    }

    @Test
    void submitProfile_rejectsNullUserId() {
        BizException ex = assertThrows(BizException.class,
                () -> service.submitProfile(null, LICENSE_NO, "BASIC"));

        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        verify(profileRepository, never()).save(any());
    }

    @Test
    void approve_activatesPendingProfile() {
        PilotProfile pending = profile(PilotStatus.PENDING, 100);
        when(profileRepository.findByUserIdAndDeletedFalse(USER_ID)).thenReturn(Optional.of(pending));
        when(profileRepository.save(any(PilotProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        PilotProfile saved = service.approve(USER_ID, APPROVER_ID);

        assertEquals(PilotStatus.ACTIVE, saved.getStatus());
        assertEquals(APPROVER_ID, saved.getApprovedBy());
        assertNotNull(saved.getApprovedAt());
    }

    @Test
    void approve_rejectsNonPendingWith409() {
        when(profileRepository.findByUserIdAndDeletedFalse(USER_ID))
                .thenReturn(Optional.of(profile(PilotStatus.ACTIVE, 100)));

        BizException ex = assertThrows(BizException.class, () -> service.approve(USER_ID, APPROVER_ID));

        assertEquals(40965, ex.getCode());
        assertEquals("error.pilot.status.invalid", ex.getMessageCode());
        verify(profileRepository, never()).save(any());
    }

    @Test
    void getProfile_throws404WhenAbsent() {
        when(profileRepository.findByUserIdAndDeletedFalse(USER_ID)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.getProfile(USER_ID));

        assertEquals(40468, ex.getCode());
        assertEquals("error.pilot.profile.not.found", ex.getMessageCode());
    }

    @Test
    void list_withoutStatusReturnsAllNotDeleted() {
        when(profileRepository.findByDeletedFalse()).thenReturn(List.of(profile(PilotStatus.ACTIVE, 100)));

        assertEquals(1, service.list(null).size());

        assertEquals(1, service.list("  ").size());
    }

    @Test
    void list_byStatusParsesEnum() {
        when(profileRepository.findByStatusAndDeletedFalse(PilotStatus.SUSPENDED))
                .thenReturn(List.of(profile(PilotStatus.SUSPENDED, 80)));

        assertEquals(1, service.list("suspended").size());
    }

    @Test
    void list_rejectsUnknownStatus() {
        BizException ex = assertThrows(BizException.class, () -> service.list("NOT_A_STATUS"));

        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        assertEquals("error.pilot.status.unknown", ex.getMessageCode());
    }

    // -------------------------------------------------------------------- 行为事件

    @Test
    void recordBehavior_normalizesSeverityAndType() {
        when(behaviorRepository.save(any(PilotBehaviorEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        PilotBehaviorEvent saved = service.recordBehavior(
                USER_ID, 10L, "geofence_hit", "high", "{\"lat\":11.5}", "TELEMETRY-1");

        assertEquals(PilotBehaviorType.GEOFENCE_HIT, saved.getEventType());
        assertEquals("HIGH", saved.getSeverity());
        assertEquals("{\"lat\":11.5}", saved.getDetailJson());
        assertNotNull(saved.getOccurredAt());
    }

    @Test
    void recordBehavior_rejectsUnknownType() {
        BizException ex = assertThrows(BizException.class,
                () -> service.recordBehavior(USER_ID, 10L, "NOT_A_TYPE", "LOW", null, null));

        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        assertEquals("error.pilot.behavior.invalid", ex.getMessageCode());
        verify(behaviorRepository, never()).save(any());
    }

    @Test
    void recordBehavior_rejectsNullUserId() {
        BizException ex = assertThrows(BizException.class,
                () -> service.recordBehavior(null, 10L, "OVERLOAD", "LOW", null, null));

        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        verify(behaviorRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------- 处罚

    @Test
    void penalize_suspendDeductsCreditAndSetsSuspended() {
        PilotProfile active = profile(PilotStatus.ACTIVE, 100);
        when(profileRepository.findByUserIdAndDeletedFalse(USER_ID)).thenReturn(Optional.of(active));
        when(penaltyRepository.save(any(PilotPenalty.class))).thenAnswer(inv -> inv.getArgument(0));
        when(profileRepository.save(any(PilotProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        PilotPenalty saved = service.penalize(USER_ID, "SUSPEND", "GEOFENCE_HIT", "high", 20,
                "TASK-9", APPROVER_ID);

        assertEquals(PilotPenaltyType.SUSPEND, saved.getPenaltyType());
        assertEquals(20, saved.getPoints());
        assertEquals("HIGH", saved.getSeverity());
        assertEquals(PROFILE_ID, saved.getPilotId());
        assertEquals(APPROVER_ID, saved.getDecidedBy());
        assertNotNull(saved.getDecidedAt());
        assertEquals(PilotStatus.SUSPENDED, active.getStatus());
        assertEquals(Integer.valueOf(80), active.getCreditScore());
    }

    @Test
    void penalize_revokeSetsBanned() {
        PilotProfile active = profile(PilotStatus.ACTIVE, 60);
        when(profileRepository.findByUserIdAndDeletedFalse(USER_ID)).thenReturn(Optional.of(active));
        when(penaltyRepository.save(any(PilotPenalty.class))).thenAnswer(inv -> inv.getArgument(0));
        when(profileRepository.save(any(PilotProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        service.penalize(USER_ID, "REVOKE", null, null, 0, null, APPROVER_ID);

        assertEquals(PilotStatus.BANNED, active.getStatus());
        assertEquals(Integer.valueOf(60), active.getCreditScore());
    }

    @Test
    void penalize_warnKeepsStatusAndFloorsCreditAtZero() {
        PilotProfile active = profile(PilotStatus.ACTIVE, 5);
        when(profileRepository.findByUserIdAndDeletedFalse(USER_ID)).thenReturn(Optional.of(active));
        when(penaltyRepository.save(any(PilotPenalty.class))).thenAnswer(inv -> inv.getArgument(0));
        when(profileRepository.save(any(PilotProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        PilotPenalty saved = service.penalize(USER_ID, "WARN", "UNSAFE_OP", "LOW", 30, null, APPROVER_ID);

        assertEquals("UNSAFE_OP", saved.getCause());
        assertEquals(PilotStatus.ACTIVE, active.getStatus());
        assertEquals(Integer.valueOf(0), active.getCreditScore(), "信用分不得为负");
    }

    @Test
    void penalize_reportsActivePenaltyCountForObservability() {
        PilotProfile active = profile(PilotStatus.ACTIVE, 100);
        when(profileRepository.findByUserIdAndDeletedFalse(USER_ID)).thenReturn(Optional.of(active));
        when(penaltyRepository.save(any(PilotPenalty.class))).thenAnswer(inv -> inv.getArgument(0));
        when(profileRepository.save(any(PilotProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(penaltyRepository.countByPilotIdAndStatusAndDeletedFalse(PROFILE_ID, "ACTIVE")).thenReturn(2L);

        PilotPenalty saved = service.penalize(USER_ID, "WARN", "UNSAFE_OP", "LOW", 5, null, APPROVER_ID);

        assertNotNull(saved);
        verify(penaltyRepository).countByPilotIdAndStatusAndDeletedFalse(PROFILE_ID, "ACTIVE");
    }

    @Test
    void penalize_survivesPenaltyCountFailure() {
        PilotProfile active = profile(PilotStatus.ACTIVE, 100);
        when(profileRepository.findByUserIdAndDeletedFalse(USER_ID)).thenReturn(Optional.of(active));
        when(penaltyRepository.save(any(PilotPenalty.class))).thenAnswer(inv -> inv.getArgument(0));
        when(profileRepository.save(any(PilotProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(penaltyRepository.countByPilotIdAndStatusAndDeletedFalse(PROFILE_ID, "ACTIVE"))
                .thenThrow(new IllegalStateException("count unavailable"));

        PilotPenalty saved = service.penalize(USER_ID, "SUSPEND", "GEOFENCE_HIT", "HIGH", 10,
                null, APPROVER_ID);

        // 观测性统计失败不得回滚处罚与状态副作用。
        assertNotNull(saved);
        assertEquals(PilotStatus.SUSPENDED, active.getStatus());
        assertEquals(Integer.valueOf(90), active.getCreditScore());
    }

    @Test
    void penalize_rejectsUnknownType() {
        when(profileRepository.findByUserIdAndDeletedFalse(USER_ID))
                .thenReturn(Optional.of(profile(PilotStatus.ACTIVE, 100)));

        BizException ex = assertThrows(BizException.class,
                () -> service.penalize(USER_ID, "NUKE", null, null, 0, null, APPROVER_ID));

        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        assertEquals("error.pilot.penalty.invalid", ex.getMessageCode());
        verify(penaltyRepository, never()).save(any());
    }

    @Test
    void penalties_returnsHistoryOfProfile() {
        when(profileRepository.findByUserIdAndDeletedFalse(USER_ID))
                .thenReturn(Optional.of(profile(PilotStatus.SUSPENDED, 80)));
        when(penaltyRepository.findByPilotIdAndDeletedFalseOrderByDecidedAtDesc(PROFILE_ID))
                .thenReturn(List.of());

        assertEquals(0, service.penalties(USER_ID).size());

        verify(penaltyRepository).findByPilotIdAndDeletedFalseOrderByDecidedAtDesc(PROFILE_ID);
    }

    @Test
    void behaviors_returnsEventsOfPilot() {
        when(behaviorRepository.findByPilotUserIdOrderByOccurredAtDesc(USER_ID)).thenReturn(List.of());

        assertEquals(0, service.behaviors(USER_ID).size());

        verify(behaviorRepository).findByPilotUserIdOrderByOccurredAtDesc(USER_ID);
    }
}
