package com.claw.server.domain.project;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.dto.ProjectDtos.*;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.BizType;
import com.claw.server.domain.asset.AssetService;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.LedgerService;
import com.claw.server.domain.sharedpool.SharedPoolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 项目管理域单元测试：项目树 CRUD、设备绑定唯一性、三态授权路由、核算接线。
 * 跨域依赖（SharedPoolService / LedgerService / AssetService）全部 mock，遵循 ArchUnit 边界。
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectDeviceRepository projectDeviceRepository;
    @Mock private DeviceAuthorizationRepository deviceAuthorizationRepository;
    @Mock private SharedPoolService sharedPoolService;
    @Mock private LedgerService ledgerService;
    @Mock private AssetService assetService;

    @InjectMocks private ProjectService service;

    private Project project(Long id, Long owner, Long parentId, int depth, int sortNo) {
        return Project.builder().id(id).ownerUserId(owner).name("P" + id)
                .parentId(parentId).depth(depth).sortNo(sortNo).accountId(100L + id).build();
    }

    // ---- 项目 CRUD + 树 ----

    @Test
    void create_builds_project_and_project_account() {
        when(ledgerService.createAccount(AccountType.PROJECT, 7L, null))
                .thenReturn(Account.builder().id(99L).build());
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectView v = service.create(new CreateProjectReq("光伏电站A", null, 0), 7L);

        assertEquals("光伏电站A", v.name());
        assertEquals(99L, v.accountId());
        assertEquals(0, v.depth());
        verify(ledgerService).createAccount(AccountType.PROJECT, 7L, null);
        verify(projectRepository).save(any(Project.class));
    }

    @Test
    void create_with_parent_sets_depth_plus_one() {
        when(ledgerService.createAccount(AccountType.PROJECT, 7L, null))
                .thenReturn(Account.builder().id(99L).build());
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L, 7L, null, 0, 0)));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectView v = service.create(new CreateProjectReq("子项目", 1L, 0), 7L);
        assertEquals(1, v.depth());
    }

    @Test
    void list_assembles_parent_child_tree() {
        Project root = project(1L, 7L, null, 0, 1);
        Project sub = Project.builder().id(2L).ownerUserId(7L).name("Sub").parentId(1L)
                .depth(1).sortNo(0).accountId(102L).build();
        when(projectRepository.findByOwnerUserIdOrderBySortNoAsc(7L)).thenReturn(List.of(root, sub));

        List<ProjectTreeNode> tree = service.listByOwner(7L);

        assertEquals(1, tree.size());
        assertEquals("P1", tree.get(0).name());
        assertEquals(1, tree.get(0).children().size());
        assertEquals("Sub", tree.get(0).children().get(0).name());
        assertEquals(1, tree.get(0).children().get(0).depth());
    }

    @Test
    void delete_unbinds_devices_and_reparents_children() {
        Project root = project(1L, 7L, null, 0, 0);
        Project child = Project.builder().id(2L).ownerUserId(7L).name("Sub").parentId(1L)
                .depth(1).sortNo(0).accountId(102L).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(root));
        when(projectRepository.findByParentId(1L)).thenReturn(List.of(child));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        service.delete(1L, 7L);

        // 子项目上提为根（parentId=null, depth=0）
        ArgumentCaptor<Project> cap = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(cap.capture());
        assertNull(cap.getValue().getParentId());
        assertEquals(0, cap.getValue().getDepth());
        verify(projectDeviceRepository).deleteByProjectId(1L);
        verify(projectRepository).delete(root);
    }

    // ---- 设备绑定 / 解绑 ----

    @Test
    void bindDevice_rejects_duplicate() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 7L, null, 0, 0)));
        when(projectDeviceRepository.existsByProjectIdAndAssetId(5L, 10L)).thenReturn(true);

        assertThrows(BizException.class, () -> service.bindDevice(5L, 10L, 7L));
        verify(projectDeviceRepository, never()).save(any());
    }

    @Test
    void bindDevice_copies_productId_from_asset() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 7L, null, 0, 0)));
        when(projectDeviceRepository.existsByProjectIdAndAssetId(5L, 10L)).thenReturn(false);
        when(assetService.getAsset(10L)).thenReturn(new ApiViews.AssetView(10L, AssetType.VEHICLE,
                "A1", null, null, null, 42L, null, 7L, 7L, AssetStatus.IN_USE, Instant.now()));
        when(projectDeviceRepository.save(any(ProjectDevice.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectDeviceView v = service.bindDevice(5L, 10L, 7L);

        assertEquals(42L, v.productId());
        assertEquals("A1", v.assetNo());
        assertEquals("VEHICLE", v.assetType());
    }

    @Test
    void unbindDevice_requires_ownership() {
        ProjectDevice pd = ProjectDevice.builder().id(3L).projectId(5L).assetId(10L).build();
        when(projectDeviceRepository.findById(3L)).thenReturn(Optional.of(pd));
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 7L, null, 0, 0)));

        service.unbindDevice(3L, 7L);
        verify(projectDeviceRepository).deleteById(3L);
    }

    // ---- 三态授权路由 ----

    @Test
    void authorize_SHARE_calls_poolAsset_and_records() {
        ProjectDevice pd = ProjectDevice.builder().id(3L).projectId(5L).assetId(10L).build();
        when(projectDeviceRepository.findById(3L)).thenReturn(Optional.of(pd));
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 7L, null, 0, 0)));
        when(deviceAuthorizationRepository.save(any(DeviceAuthorization.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.authorize(3L, new DeviceAuthorizeReq("SHARE", null, "use", 1L,
                null, null, null, null), 7L);

        verify(sharedPoolService).poolAsset(eq(10L), eq(7L), eq(1L),
                isNull(), isNull(), isNull(), isNull());
        verify(assetService, never()).listInHall(any(), any());
        ArgumentCaptor<DeviceAuthorization> cap = ArgumentCaptor.forClass(DeviceAuthorization.class);
        verify(deviceAuthorizationRepository).save(cap.capture());
        assertEquals("SHARE", cap.getValue().getAuthType());
    }

    @Test
    void authorize_TRANSFER_calls_listInHall_and_records_TRANSFER() {
        ProjectDevice pd = ProjectDevice.builder().id(3L).projectId(5L).assetId(10L).build();
        when(projectDeviceRepository.findById(3L)).thenReturn(Optional.of(pd));
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 7L, null, 0, 0)));
        when(deviceAuthorizationRepository.save(any(DeviceAuthorization.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DeviceAuthorizationView v = service.authorize(3L,
                new DeviceAuthorizeReq("TRANSFER", null, "ownership", null,
                        null, null, null, null), 7L);

        assertEquals("TRANSFER", v.authType());
        verify(assetService).listInHall(10L, 7L);
        verify(sharedPoolService, never()).poolAsset(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void authorize_AUTHORIZE_only_records_no_cross_domain_side_effect() {
        ProjectDevice pd = ProjectDevice.builder().id(3L).projectId(5L).assetId(10L).build();
        when(projectDeviceRepository.findById(3L)).thenReturn(Optional.of(pd));
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 7L, null, 0, 0)));
        when(deviceAuthorizationRepository.save(any(DeviceAuthorization.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DeviceAuthorizationView v = service.authorize(3L,
                new DeviceAuthorizeReq("AUTHORIZE", 9L, "use,locate", null,
                        null, null, null, null), 7L);

        assertEquals("AUTHORIZE", v.authType());
        assertEquals(9L, v.granteeUserId());
        verify(assetService, never()).listInHall(any(), any());
        verify(sharedPoolService, never()).poolAsset(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void authorize_invalid_type_rejected() {
        ProjectDevice pd = ProjectDevice.builder().id(3L).projectId(5L).assetId(10L).build();
        when(projectDeviceRepository.findById(3L)).thenReturn(Optional.of(pd));
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 7L, null, 0, 0)));

        assertThrows(BizException.class, () -> service.authorize(3L,
                new DeviceAuthorizeReq("NOPE", null, null, null, null, null, null, null), 7L));
    }

    // ---- 每项目核算 ----

    @Test
    void recordProjectEntry_posts_balanced_double_entry_via_project_account() {
        Project p = project(5L, 7L, null, 0, 0); // accountId = 105
        when(projectRepository.findById(5L)).thenReturn(Optional.of(p));
        when(ledgerService.getPlatformAccountId()).thenReturn(100L);
        when(ledgerService.postEntries(any(), any(), any()))
                .thenReturn(new LedgerViews.TxnResult(UUID.randomUUID(), BizType.PROJECT_LEDGER,
                        "ref", 2, BigDecimal.ONE, List.of()));

        service.recordProjectEntry(5L, new BigDecimal("100.00"), "INCOME", "租金收入", 7L);

        ArgumentCaptor<List<LedgerRequests.Entry>> cap = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postEntries(eq(BizType.PROJECT_LEDGER), any(), cap.capture());
        List<LedgerRequests.Entry> entries = cap.getValue();
        assertEquals(2, entries.size());
        // 项目账户(105) 贷、平台账户(100) 借，借贷平衡
        assertTrue(entries.stream().anyMatch(e -> e.accountId().equals(105L)
                && e.direction() == LedgerRequests.Direction.C));
        assertTrue(entries.stream().anyMatch(e -> e.accountId().equals(100L)
                && e.direction() == LedgerRequests.Direction.D));
    }

    @Test
    void recordProjectEntry_expense_debits_project_account() {
        Project p = project(5L, 7L, null, 0, 0); // accountId = 105
        when(projectRepository.findById(5L)).thenReturn(Optional.of(p));
        when(ledgerService.getPlatformAccountId()).thenReturn(100L);
        when(ledgerService.postEntries(any(), any(), any()))
                .thenReturn(new LedgerViews.TxnResult(UUID.randomUUID(), BizType.PROJECT_LEDGER,
                        "ref", 2, BigDecimal.ONE, List.of()));

        service.recordProjectEntry(5L, new BigDecimal("50.00"), "EXPENSE", "运维支出", 7L);

        ArgumentCaptor<List<LedgerRequests.Entry>> cap = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postEntries(eq(BizType.PROJECT_LEDGER), any(), cap.capture());
        List<LedgerRequests.Entry> entries = cap.getValue();
        assertTrue(entries.stream().anyMatch(e -> e.accountId().equals(105L)
                && e.direction() == LedgerRequests.Direction.D));
    }
}
