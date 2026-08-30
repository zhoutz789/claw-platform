package com.claw.server.domain.asset;

import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.security.DataScopeContext;
import com.claw.server.common.security.DataScopeResult;
import com.claw.server.common.security.DataScopeResult.Scope;
import com.claw.server.domain.custody.CustodyService;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.iot.TelemetryRepository;
import com.claw.server.domain.role.DataScopeService;
import com.claw.server.domain.role.PermissionService;
import com.claw.server.domain.role.RoleGrantService;
import com.claw.server.domain.user.UserRepository;
import com.claw.server.test.scope.CriteriaProbeHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AssetService#listAssets} 的数据范围接线测试（权限通电 P1-T03 决策③a）。
 *
 * <p>只验证「接线」这一件事——@DataScope 标注的列表方法是否正确消费
 * {@link DataScopeContext}：
 * <ul>
 *   <li>上下文为 ALL（开发态放开写入的值）→ 不构造过滤谓词，全部数据可见；</li>
 *   <li>上下文为非 ALL → 走 {@code DataScopeSpec} 构造谓词，
 *       且<b>绝不</b>回落调用 {@code dataScopeService.resolve(...)}。</li>
 * </ul>
 * 谓词本身的翻译正确性由 {@code DataScopeSpecTest} 权威覆盖，此处不重复。
 */
@ExtendWith(MockitoExtension.class)
class AssetServiceDataScopeTest {

    @Mock
    private AssetRepository assetRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private BatteryRepository batteryRepository;
    @Mock
    private AssetStatusLogRepository statusLogRepository;
    @Mock
    private AssetLifecycleEventRepository lifecycleEventRepository;
    @Mock
    private UserAssetsAclRepository aclRepository;
    @Mock
    private PermissionService permissionService;
    @Mock
    private RoleGrantService roleGrantService;
    @Mock
    private DataScopeService dataScopeService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CustodyService custodyService;
    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private DroneRepository droneRepository;
    @Mock
    private TelemetryRepository telemetryRepository;
    @Mock
    private AssetMaintenanceRecordRepository maintenanceRecordRepository;

    @InjectMocks
    private AssetService assetService;

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }

    @Test
    @DisplayName("上下文为 ALL → 不过滤，三条资产全部返回（开发态放开的落地效果）")
    void allScope_returnsEveryAsset() {
        when(assetRepository.findAll(any(Specification.class))).thenReturn(threeAssets());
        DataScopeContext.set(DataScopeResult.all());

        List<ApiViews.AssetView> views = assetService.listAssets(null, null);

        assertEquals(3, views.size(), "ALL 不应过滤掉任何资产");
        assertEquals(List.of(1L, 2L, 3L), views.stream().map(ApiViews.AssetView::id).toList(),
                "应原样返回仓储给出的三条资产");
        verify(dataScopeService, never()).resolve(any());
    }

    @Test
    @DisplayName("上下文为 ALL → 传给仓储的 Specification 不含过滤谓词")
    void allScope_passesNonFilteringSpecification() {
        when(assetRepository.findAll(any(Specification.class))).thenReturn(threeAssets());
        DataScopeContext.set(DataScopeResult.all());

        assetService.listAssets(null, null);

        ArgumentCaptor<Specification<Asset>> captor = specCaptor();
        verify(assetRepository).findAll(captor.capture());

        try (CriteriaProbeHarness harness = CriteriaProbeHarness.bootstrap()) {
            var predicate = harness.translate(captor.getValue());
            assertNotNull(predicate, "ALL 分支应给出 conjunction");
            assertTrue(predicate.getExpressions().isEmpty(), "ALL 分支不应含任何过滤子表达式");
        }
    }

    @Test
    @DisplayName("上下文为非 ALL（CUSTOM）→ 经 DataScopeSpec 构造谓词，绝不回落 resolve()")
    void customScope_buildsSpecWithoutFallbackResolve() {
        when(assetRepository.findAll(any(Specification.class))).thenReturn(threeAssets());
        DataScopeContext.set(new DataScopeResult(
                Scope.CUSTOM, null, Set.of(), Set.of(1001L, 1002L), Set.of(), 42L));

        List<ApiViews.AssetView> views = assetService.listAssets(null, null);

        assertEquals(3, views.size(), "仓储被 mock，返回条数由 mock 决定；重点是不抛异常且走了 Spec 路径");
        // 关键断言：一旦回落到 resolve()，开发态放开与切面写入的语义就被绕过了
        verify(dataScopeService, never()).resolve(any());
        verify(assetRepository).findAll(any(Specification.class));
    }

    @Test
    @DisplayName("上下文为非 ALL（SELF）→ 同样不回落 resolve()")
    void selfScope_doesNotFallbackToResolve() {
        when(assetRepository.findAll(any(Specification.class))).thenReturn(threeAssets());
        DataScopeContext.set(new DataScopeResult(Scope.SELF, null, Set.of(), Set.of(), Set.of(), 42L));

        assetService.listAssets(null, null);

        verify(dataScopeService, never()).resolve(any());
    }

    @Test
    @DisplayName("类型入参仍在数据范围之上二次过滤（两者叠加而非互相覆盖）")
    void assetTypeFilterAppliesOnTopOfDataScope() {
        when(assetRepository.findAll(any(Specification.class))).thenReturn(threeAssets());
        DataScopeContext.set(DataScopeResult.all());

        List<ApiViews.AssetView> views = assetService.listAssets(AssetType.VEHICLE, null);

        assertEquals(2, views.size(), "三条资产中有两条 VEHICLE");
        assertTrue(views.stream().allMatch(v -> v.assetType() == AssetType.VEHICLE),
                "过滤后应只剩 VEHICLE");
    }

    // ---------- 工具方法 ----------

    private static List<Asset> threeAssets() {
        return List.of(
                Asset.builder().id(1L).assetType(AssetType.VEHICLE).assetNo("A-001")
                        .ownerId(42L).status(AssetStatus.IN_STOCK).build(),
                Asset.builder().id(2L).assetType(AssetType.BATTERY).assetNo("A-002")
                        .ownerId(99L).status(AssetStatus.IN_STOCK).build(),
                Asset.builder().id(3L).assetType(AssetType.VEHICLE).assetNo("A-003")
                        .ownerId(7L).status(AssetStatus.IN_USE).build());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Specification<Asset>> specCaptor() {
        return ArgumentCaptor.forClass(Specification.class);
    }
}
