package com.claw.server.domain.airspace;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.DroneSafetyCause;
import com.claw.server.common.enums.DroneSafetyEventStatus;
import com.claw.server.common.enums.DroneSafetyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DroneSafetyService 纯逻辑单测（mock repository，无 PG 依赖）。
 * 覆盖：安全态判定 / FIFO 确定性解除 / 无事件解除抛 40961 / 模拟触发 / 列表。
 */
class DroneSafetyServiceTest {

    private DroneSafetyEventRepository repo;
    private DroneSafetyService service;

    @BeforeEach
    void init() {
        repo = mock(DroneSafetyEventRepository.class);
        service = new DroneSafetyService(repo);
    }

    private DroneSafetyEvent event(Long id, DroneSafetyCause cause,
                                   DroneSafetyEventStatus status, Instant createdAt) {
        return DroneSafetyEvent.builder()
                .id(id)
                .assetId(100L)
                .cause(cause)
                .status(status)
                .createdAt(createdAt)
                .build();
    }

    /** 存在 OPEN 事件即判定 LOCKED（类比车辆断缴锁车）。 */
    @Test
    void currentStatus_openEvent_returnsLocked() {
        when(repo.existsByAssetIdAndStatus(100L, DroneSafetyEventStatus.OPEN)).thenReturn(true);

        assertEquals(DroneSafetyStatus.LOCKED, service.currentStatus(100L));
        verify(repo).existsByAssetIdAndStatus(100L, DroneSafetyEventStatus.OPEN);
    }

    /** 无 OPEN 事件判定 NORMAL。 */
    @Test
    void currentStatus_noOpenEvent_returnsNormal() {
        when(repo.existsByAssetIdAndStatus(100L, DroneSafetyEventStatus.OPEN)).thenReturn(false);

        assertEquals(DroneSafetyStatus.NORMAL, service.currentStatus(100L));
    }

    /** 不同资产互不影响：只有目标资产有 OPEN 才锁。 */
    @Test
    void currentStatus_otherAssetOpenDoesNotLockThisAsset() {
        when(repo.existsByAssetIdAndStatus(100L, DroneSafetyEventStatus.OPEN)).thenReturn(false);
        when(repo.existsByAssetIdAndStatus(999L, DroneSafetyEventStatus.OPEN)).thenReturn(true);

        assertEquals(DroneSafetyStatus.NORMAL, service.currentStatus(100L));
        assertEquals(DroneSafetyStatus.LOCKED, service.currentStatus(999L));
    }

    /** 解除必须命中「最早触发」的 OPEN 事件（FIFO 确定性语义）。 */
    @Test
    void resolve_fifoResolvesEarliestOpenEvent() {
        Instant earlier = Instant.parse("2026-09-06T08:00:00Z");
        Instant later = Instant.parse("2026-09-06T09:30:00Z");
        DroneSafetyEvent first = event(1L, DroneSafetyCause.GEOFENCE_VIOLATION, DroneSafetyEventStatus.OPEN, earlier);
        DroneSafetyEvent second = event(2L, DroneSafetyCause.LOW_BATTERY, DroneSafetyEventStatus.OPEN, later);

        when(repo.findFirstByAssetIdAndStatusOrderByCreatedAtAsc(100L, DroneSafetyEventStatus.OPEN))
                .thenReturn(Optional.of(first));
        when(repo.save(first)).thenReturn(first);

        DroneSafetyEvent resolved = service.resolve(100L);

        assertSame(first, resolved);
        assertEquals(DroneSafetyEventStatus.RESOLVED, resolved.getStatus());
        assertNotNull(resolved.getResolvedAt());
        verify(repo, never()).save(second);
    }

    /** 无 OPEN 事件解除 → 抛 40961 状态冲突（非服务端故障）。 */
    @Test
    void resolve_noOpenEvent_throwsBizException40961() {
        when(repo.findFirstByAssetIdAndStatusOrderByCreatedAtAsc(100L, DroneSafetyEventStatus.OPEN))
                .thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.resolve(100L));
        assertEquals(40961, ex.getCode());
    }

    /** 模拟触发：落地一条 OPEN 事件并回填 cause。 */
    @Test
    void simulate_createsOpenEventWithCause() {
        DroneSafetyEvent saved = event(7L, DroneSafetyCause.MANUAL, DroneSafetyEventStatus.OPEN, Instant.now());
        when(repo.save(any(DroneSafetyEvent.class))).thenReturn(saved);

        DroneSafetyEvent e = service.simulate(100L, DroneSafetyCause.MANUAL, "测试锁机");

        assertEquals(7L, e.getId());
        assertEquals(DroneSafetyCause.MANUAL, e.getCause());
        assertEquals(DroneSafetyEventStatus.OPEN, e.getStatus());
        verify(repo).save(any(DroneSafetyEvent.class));
    }

    /** 列表按创建时间倒序返回。 */
    @Test
    void listEvents_returnsDescOrderedList() {
        List<DroneSafetyEvent> list = List.of(
                event(1L, DroneSafetyCause.LOST_LINK, DroneSafetyEventStatus.OPEN, Instant.now()));
        when(repo.findByAssetIdOrderByCreatedAtDesc(100L)).thenReturn(list);

        assertSame(list, service.listEvents(100L));
    }
}
