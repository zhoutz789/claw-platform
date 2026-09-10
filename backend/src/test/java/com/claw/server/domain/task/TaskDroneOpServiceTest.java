package com.claw.server.domain.task;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.TaskRequests;
import com.claw.server.common.dto.TaskViews;
import com.claw.server.common.enums.AssetCapability;
import com.claw.server.common.enums.DroneMissionType;
import com.claw.server.common.enums.TaskStatus;
import com.claw.server.common.enums.TaskType;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.payload.DroneMission;
import com.claw.server.domain.payload.DroneMissionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
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
 * TaskService P3 单元测试（无 DB，Mockito）：
 * <ol>
 *   <li>DRONE_OP 发布时创建 DroneMission 并回写 task.drone_mission_id，TaskView.drone 含作业明细；</li>
 *   <li>非法 missionType 抛 BizException；</li>
 *   <li>provider 可接单列表按 geo 距离过滤（超出 service_radius_m 的任务剔除）。</li>
 * </ol>
 * 结算（TaskSettlementService）不在本测试范围，仅注入不调用。
 */
@ExtendWith(MockitoExtension.class)
class TaskDroneOpServiceTest {

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
    @Mock
    private DroneMissionRepository droneMissionRepository;

    @InjectMocks
    private TaskService taskService;

    @Test
    void publish_droneOp_createsDroneMissionAndLinksTask() {
        TaskRequests.Publish req = droneReq("SPRAY");

        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(10L);
            return t;
        });
        DroneMission[] missionHolder = new DroneMission[1];
        when(droneMissionRepository.save(any(DroneMission.class))).thenAnswer(inv -> {
            DroneMission m = inv.getArgument(0);
            m.setId(77L);
            missionHolder[0] = m;
            return m;
        });
        when(droneMissionRepository.findById(77L)).thenAnswer(inv -> Optional.ofNullable(missionHolder[0]));

        TaskViews.TaskView view = taskService.publish(req, 1L);

        ArgumentCaptor<DroneMission> captor = ArgumentCaptor.forClass(DroneMission.class);
        verify(droneMissionRepository, times(1)).save(captor.capture());
        DroneMission saved = captor.getValue();
        assertEquals(55L, saved.getAssetId());
        assertEquals(88L, saved.getPilotId());
        assertEquals(DroneMissionType.SPRAY, saved.getMissionType());
        assertNotNull(saved.getExecutedAt());
        assertEquals(new BigDecimal("12.50"), saved.getAreaHa());
        assertEquals(3, saved.getTrips());
        assertEquals(45, saved.getFlightMinutes());

        assertEquals(77L, view.droneMissionId());
        Map<String, Object> drone = view.drone();
        assertNotNull(drone);
        assertEquals("SPRAY", drone.get("missionType"));
        assertEquals("pesticide", drone.get("payloadDesc"));
        assertEquals(55L, drone.get("assetId"));
        assertEquals(88L, drone.get("pilotId"));
    }

    @Test
    void publish_droneOp_withInvalidMissionType_throwsBizException() {
        TaskRequests.Publish req = droneReq("BOGUS");

        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(10L);
            return t;
        });

        BizException ex = assertThrows(BizException.class, () -> taskService.publish(req, 1L));
        assertEquals("error.task.drone.mission.type", ex.getMessageCode());
    }

    @Test
    void listAvailableForProvider_filtersTasksOutsideServiceRadius() {
        // provider 坐标：上海人民广场附近
        BigDecimal lat = new BigDecimal("31.23040000");
        BigDecimal lng = new BigDecimal("121.47370000");

        // 约 5 km 之外（约 0.045° 纬度差），服务半径仅 1 km → 应被剔除
        Task far = Task.builder()
                .id(1L)
                .publisherId(2L)
                .taskType(TaskType.DRONE_OP)
                .title("Far spray job")
                .rewardAmount(new BigDecimal("50.00"))
                .currency("USD")
                .capabilityRequired(AssetCapability.DRONE_OP)
                .status(TaskStatus.OPEN)
                .geoLat(new BigDecimal("31.27530000"))
                .geoLng(new BigDecimal("121.47370000"))
                .serviceRadiusM(1000)
                .build();
        // 约 200 m（约 0.0018° 纬度差），服务半径 1 km → 应保留
        Task near = Task.builder()
                .id(2L)
                .publisherId(2L)
                .taskType(TaskType.DRONE_OP)
                .title("Near spray job")
                .rewardAmount(new BigDecimal("50.00"))
                .currency("USD")
                .capabilityRequired(AssetCapability.DRONE_OP)
                .status(TaskStatus.OPEN)
                .geoLat(new BigDecimal("31.23220000"))
                .geoLng(new BigDecimal("121.47370000"))
                .serviceRadiusM(1000)
                .build();

        when(assetRepository.findByUserIdAndDeletedFalse(9L))
                .thenReturn(List.of(Asset.builder().id(5L).capabilities("DRONE_OP").build()));
        when(taskRepository.findByStatusAndCapabilityRequired(TaskStatus.OPEN, AssetCapability.DRONE_OP))
                .thenReturn(List.of(far, near));
        when(taskAssignmentRepository.findByProviderId(9L)).thenReturn(List.of());

        List<TaskViews.TaskView> views = taskService.listAvailableForProvider(9L, lat, lng);

        assertEquals(1, views.size());
        assertEquals(2L, views.get(0).id());
        assertEquals("Near spray job", views.get(0).title());
    }

    /** DRONE_OP 发布请求（missionType 可变，其余固定）。 */
    private static TaskRequests.Publish droneReq(String missionType) {
        return new TaskRequests.Publish(
                TaskType.DRONE_OP,
                "Field spraying",
                "north parcel",
                new BigDecimal("120.00"),
                "USD",
                AssetCapability.DRONE_OP,
                new BigDecimal("31.23040000"), new BigDecimal("121.47370000"), 2000,
                null, null, null, null,                       // pickup/dropoff/cargo/weight
                null, null, null, null, null, null,           // origin/dest/rideType/estDistance/estDuration/fareModel
                null, null, null, null,                       // advertiser/media/display/screen
                missionType, "pesticide",                     // missionType/payloadDesc
                new BigDecimal("12.50"), 3, 45,               // areaHa/trips/flightMinutes
                88L, 55L, null);                              // pilotId/assetId/executedAt
    }
}
