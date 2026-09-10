package com.claw.server.domain.task;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.TaskRequests;
import com.claw.server.common.dto.TaskViews;
import com.claw.server.common.enums.AssetCapability;
import com.claw.server.common.enums.TaskType;
import com.claw.server.domain.asset.AssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskService P2 扩展单元测试（无 DB，Mockito）：验证 HAIL_RIDE / TAXI / AD 的发布落库扩展表，
 * 以及返回 TaskView 上附带 ride / ad 明细 map；并验证 capability 不匹配时抛 BizException。
 * 结算（TaskSettlementService）不在本测试范围，仅注入不调用。
 */
@ExtendWith(MockitoExtension.class)
class TaskRideAdServiceTest {

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private TaskAssignmentRepository taskAssignmentRepository;
    @Mock
    private TaskLogisticsRepository taskLogisticsRepository;
    @Mock
    private TaskRideRepository taskRideRepository;
    @Mock
    private TaskAdRepository taskAdRepository;
    @Mock
    private AssetRepository assetRepository;
    @Mock
    private TaskSettlementService taskSettlementService;

    @InjectMocks
    private TaskService taskService;

    @Test
    void publish_hailRide_persistsTaskRideAndAttachesRideMap() {
        TaskRequests.Publish req = new TaskRequests.Publish(
                TaskType.HAIL_RIDE,
                "Downtown run",
                "quick trip",
                new BigDecimal("3.50"),
                "USD",
                AssetCapability.RIDE_HAIL,
                new BigDecimal("1.23"),
                new BigDecimal("4.56"),
                500,
                null, null, null, null,                       // pickup/dropoff/cargo/weight
                "Main St", "5th Ave", "HAIL",                  // origin/dest/rideType
                new BigDecimal("2.40"), 12, "PER_KM",          // estDistance/estDuration/fareModel
                null, null, null, null);                       // advertiser/media/display/screen

        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(10L);
            return t;
        });
        TaskRide[] rideHolder = new TaskRide[1];
        when(taskRideRepository.save(any(TaskRide.class))).thenAnswer(inv -> {
            rideHolder[0] = inv.getArgument(0);
            return rideHolder[0];
        });
        when(taskRideRepository.findById(10L)).thenAnswer(inv -> Optional.ofNullable(rideHolder[0]));

        TaskViews.TaskView view = taskService.publish(req, 1L);

        ArgumentCaptor<TaskRide> rideCaptor = ArgumentCaptor.forClass(TaskRide.class);
        verify(taskRideRepository, times(1)).save(rideCaptor.capture());
        TaskRide saved = rideCaptor.getValue();
        assertEquals(10L, saved.getTaskId());
        assertEquals("HAIL", saved.getRideType());

        Map<String, Object> rideMap = view.ride();
        assertNotNull(rideMap);
        assertEquals("HAIL", rideMap.get("rideType"));
        assertEquals("Main St", rideMap.get("originAddr"));
        assertEquals("5th Ave", rideMap.get("destAddr"));
        assertEquals("PER_KM", rideMap.get("fareModel"));
    }

    @Test
    void publish_ad_persistsTaskAdAndAttachesAdMap() {
        TaskRequests.Publish req = new TaskRequests.Publish(
                TaskType.AD,
                "Promo banner",
                "ad spot",
                new BigDecimal("9.00"),
                "USD",
                AssetCapability.AD_DISPLAY,
                null, null, null,
                null, null, null, null,
                null, null, null, null, null, null,
                "Acme Co", "http://cdn/media.png", "30s", "BODY");

        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(10L);
            return t;
        });
        TaskAd[] adHolder = new TaskAd[1];
        when(taskAdRepository.save(any(TaskAd.class))).thenAnswer(inv -> {
            adHolder[0] = inv.getArgument(0);
            return adHolder[0];
        });
        when(taskAdRepository.findById(10L)).thenAnswer(inv -> Optional.ofNullable(adHolder[0]));

        TaskViews.TaskView view = taskService.publish(req, 1L);

        ArgumentCaptor<TaskAd> adCaptor = ArgumentCaptor.forClass(TaskAd.class);
        verify(taskAdRepository, times(1)).save(adCaptor.capture());
        TaskAd saved = adCaptor.getValue();
        assertEquals(10L, saved.getTaskId());
        assertEquals("BODY", saved.getScreenType());

        Map<String, Object> adMap = view.ad();
        assertNotNull(adMap);
        assertEquals("BODY", adMap.get("screenType"));
        assertEquals("Acme Co", adMap.get("advertiser"));
        assertEquals("http://cdn/media.png", adMap.get("mediaUrl"));
    }

    @Test
    void publish_ad_withRideCapability_throwsBizException() {
        TaskRequests.Publish req = new TaskRequests.Publish(
                TaskType.AD,
                "Promo banner",
                "ad spot",
                new BigDecimal("9.00"),
                "USD",
                AssetCapability.RIDE_HAIL,                    // 与 AD 预期 AD_DISPLAY 不匹配
                null, null, null,
                null, null, null, null,
                null, null, null, null, null, null,
                "Acme Co", "http://cdn/media.png", "30s", "BODY");

        BizException ex = assertThrows(BizException.class, () -> taskService.publish(req, 1L));
        assertEquals("error.task.capability.mismatch", ex.getMessageCode());
    }
}
