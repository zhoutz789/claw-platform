package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.security.DataScopeContext;
import com.claw.server.common.security.DataScopeResult;
import com.claw.server.common.security.DataScopeResult.Scope;
import com.claw.server.domain.role.DataScopeService;
import com.claw.server.domain.role.PermissionService;
import com.claw.server.domain.role.RoleGrantService;
import com.claw.server.domain.role.RoleRepository;
import com.claw.server.domain.role.UserRolePackageRepository;
import com.claw.server.domain.user.User;
import com.claw.server.domain.user.UserRepository;
import com.claw.server.test.scope.CriteriaProbeHarness;
import com.claw.server.test.scope.CriteriaProbeHarness.ProbeQuery;
import com.claw.server.test.scope.ScopeProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminUserController#listUsers()} 的数据范围接线测试（权限通电 P1-T03 决策③a）。
 *
 * <p>用户域是三个试点里唯一<b>实体直带 departmentId 列</b>的，因此这里除了验证 ALL 不过滤，
 * 还能把控制器真实构造出的 {@code Specification<User>} 捕获下来、在内存 H2 的探针实体上
 * <b>真实执行</b>，从而证明 DEPARTMENT 范围确实生成了按 departmentId 的过滤——
 * 而不只是「构造了一个非空对象」。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserControllerDataScopeTest {

    private static CriteriaProbeHarness harness;

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleGrantService roleGrantService;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserRolePackageRepository packageRepository;
    @Mock
    private DataScopeService dataScopeService;
    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private AdminUserController controller;

    @BeforeAll
    static void beforeAll() {
        harness = CriteriaProbeHarness.bootstrap();
        // 探针行：id | ownerId | departmentId | orgCode | type
        harness.seedProbes(List.of(
                new ScopeProbe(1L, 42L, 5L, "A01-01", "VEHICLE"),
                new ScopeProbe(2L, 99L, 5L, "A01-02", "VEHICLE"),
                new ScopeProbe(3L, 7L, 88L, "B02-01", "BATTERY")));
    }

    @AfterAll
    static void afterAll() {
        if (harness != null) {
            harness.close();
        }
    }

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }

    @Test
    @DisplayName("上下文为 ALL → 两个用户全部返回，且不回落 resolve()")
    void allScope_returnsEveryUser() {
        when(userRepository.findAll(any(Specification.class))).thenReturn(twoUsers());
        when(roleGrantService.listActive(anyLong())).thenReturn(List.of());
        DataScopeContext.set(DataScopeResult.all());

        ApiResult<List<ApiViews.AdminUserView>> result = controller.listUsers();

        assertNotNull(result.data(), "返回体不应为空");
        assertEquals(2, result.data().size(), "ALL 不应过滤掉任何用户");
        assertEquals(List.of(10L, 11L), result.data().stream().map(ApiViews.AdminUserView::id).toList(),
                "应原样返回仓储给出的两个用户");
        verify(dataScopeService, never()).resolve(any());
    }

    @Test
    @DisplayName("上下文为 ALL → 传给仓储的 Specification 不含过滤谓词")
    void allScope_passesNonFilteringSpecification() {
        when(userRepository.findAll(any(Specification.class))).thenReturn(twoUsers());
        when(roleGrantService.listActive(anyLong())).thenReturn(List.of());
        DataScopeContext.set(DataScopeResult.all());

        controller.listUsers();

        ArgumentCaptor<Specification<User>> captor = specCaptor();
        verify(userRepository).findAll(captor.capture());

        ProbeQuery query = harness.executeAsProbe(captor.getValue());
        assertEquals(List.of(1L, 2L, 3L), query.ids(), "ALL 分支执行后不应过滤掉任何行");
    }

    @Test
    @DisplayName("上下文为 DEPARTMENT(5) → 真实构造出按 departmentId 过滤的 Specification")
    void departmentScope_buildsDepartmentIdPredicate() {
        when(userRepository.findAll(any(Specification.class))).thenReturn(twoUsers());
        when(roleGrantService.listActive(anyLong())).thenReturn(List.of());
        DataScopeContext.set(new DataScopeResult(Scope.DEPARTMENT, 5L, Set.of(), Set.of(), Set.of(), 1L));

        controller.listUsers();

        ArgumentCaptor<Specification<User>> captor = specCaptor();
        verify(userRepository).findAll(captor.capture());
        Specification<User> spec = captor.getValue();
        assertNotNull(spec, "非 ALL 范围必须构造出 Specification");

        // 在探针实体（同样带 departmentId 列）上真实执行，证明过滤语义确实生效
        ProbeQuery query = harness.executeAsProbe(spec);
        assertTrue(query.sql().contains("departmentId"),
                () -> "SQL 应按 departmentId 过滤，实际：" + query.sql());
        assertEquals(List.of(1L, 2L), query.ids(),
                "DEPARTMENT(5) 应只命中 departmentId=5 的行，departmentId=88 的行必须被排除");
        verify(dataScopeService, never()).resolve(any());
    }

    @Test
    @DisplayName("上下文为 CUSTOM → 按 departmentId IN 过滤（用户域直带列，不走子查询）")
    void customScope_buildsDepartmentIdInPredicate() {
        when(userRepository.findAll(any(Specification.class))).thenReturn(twoUsers());
        when(roleGrantService.listActive(anyLong())).thenReturn(List.of());
        DataScopeContext.set(new DataScopeResult(
                Scope.CUSTOM, null, Set.of(), Set.of(5L, 88L), Set.of(), 1L));

        controller.listUsers();

        ArgumentCaptor<Specification<User>> captor = specCaptor();
        verify(userRepository).findAll(captor.capture());

        ProbeQuery query = harness.executeAsProbe(captor.getValue());
        assertTrue(query.sql().contains("departmentId"),
                () -> "SQL 应按 departmentId 过滤，实际：" + query.sql());
        assertTrue(query.sql().toLowerCase().contains(" in "),
                () -> "CUSTOM 应使用 IN，实际：" + query.sql());
        assertEquals(List.of(1L, 2L, 3L), query.ids(), "自定义部门集合 {5,88} 覆盖全部探针行");
    }

    // ---------- 工具方法 ----------

    private static List<User> twoUsers() {
        return List.of(
                User.builder().id(10L).phone("85510000001").fullName("张三").departmentId(5L).build(),
                User.builder().id(11L).phone("85510000002").fullName("李四").departmentId(5L).build());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Specification<User>> specCaptor() {
        return ArgumentCaptor.forClass(Specification.class);
    }
}
