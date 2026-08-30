package com.claw.server.domain.role;

import com.claw.server.common.api.BizException;
import com.claw.server.common.security.DataScopeResult;
import com.claw.server.domain.user.Department;
import com.claw.server.domain.user.DepartmentRepository;
import com.claw.server.domain.user.User;
import com.claw.server.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 数据范围解析器单元测试（Mockito 模拟仓储，无需 DB）。
 *
 * <p>覆盖：① user_roles 中的 SUPER_ADMIN → ALL；② DEPARTMENT_AND_BELOW 前缀 LIKE；
 * ③ CUSTOM 部门集合 IN；④ TYPE 类型并集；⑤ RuleValueResolver 的 #{sys_user_id} 替换；
 * ⑥ SQL 模式拒绝 DDL。
 */
@ExtendWith(MockitoExtension.class)
class DataScopeServiceTest {

    @Mock
    private UserRolePackageRepository packageRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private DepartmentRepository departmentRepository;
    @Mock
    private RolePermissionRepository rolePermissionRepository;
    @Mock
    private PermissionDataRuleRepository dataRuleRepository;
    @Mock
    private RuleValueResolver ruleValueResolver;

    @InjectMocks
    private DataScopeService dataScopeService;

    @Test
    void superAdminViaUserRoles_resolvesAll() {
        User u = User.builder().id(10L).departmentId(1L).build();
        when(userRepository.findById(10L)).thenReturn(Optional.of(u));
        when(userRoleRepository.findByUserId(10L)).thenReturn(List.of(ur(99L)));
        when(packageRepository.findByUserId(10L)).thenReturn(List.of());
        Role sa = Role.builder().id(99L).code("SUPER_ADMIN").grants("[\"*\"]").dataScope("ALL").build();
        when(roleRepository.findById(99L)).thenReturn(Optional.of(sa));

        DataScopeResult r = dataScopeService.resolve(10L);
        assertEquals(DataScopeResult.Scope.ALL, r.scope());
        assertTrue(r.isAll());
    }

    @Test
    void departmentAndBelow_usesOrgCodePrefix() {
        User u = User.builder().id(10L).departmentId(5L).build();
        when(userRepository.findById(10L)).thenReturn(Optional.of(u));
        when(departmentRepository.findById(5L)).thenReturn(Optional.of(Department.builder().id(5L).orgCode("A01").build()));
        when(userRoleRepository.findByUserId(10L)).thenReturn(List.of());
        when(packageRepository.findByUserId(10L)).thenReturn(List.of(pkg(7L)));
        Role role = Role.builder().id(7L).code("MGR").dataScope("DEPARTMENT_AND_BELOW").build();
        when(roleRepository.findById(7L)).thenReturn(Optional.of(role));

        DataScopeResult r = dataScopeService.resolve(10L);
        assertEquals(DataScopeResult.Scope.DEPARTMENT_AND_BELOW, r.scope());
        assertTrue(r.orgCodePrefixes().contains("A01"));
    }

    @Test
    void customCollectsDeptIds() {
        User u = User.builder().id(10L).departmentId(1L).build();
        when(userRepository.findById(10L)).thenReturn(Optional.of(u));
        when(userRoleRepository.findByUserId(10L)).thenReturn(List.of());
        when(packageRepository.findByUserId(10L)).thenReturn(List.of(pkg(8L)));
        Role role = Role.builder().id(8L).code("REGION").dataScope("CUSTOM").dataRuleIds("1001,1002").build();
        when(roleRepository.findById(8L)).thenReturn(Optional.of(role));

        DataScopeResult r = dataScopeService.resolve(10L);
        assertEquals(DataScopeResult.Scope.CUSTOM, r.scope());
        assertTrue(r.deptIds().contains(1001L));
        assertTrue(r.deptIds().contains(1002L));
    }

    /**
     * 复核点：前端 Roles.jsx 在 CUSTOM 数据范围下把 dataRuleIds 以 JSON 数组字符串（如
     * ["1001","1002"]）提交到 PUT /api/v1/admin/roles/{id}；后端 parseDeptIds 必须能正确解析
     * 出两个 Long，而非静默得到空集（QA 新增用例）。
     */
    @Test
    void customDeptIds_jsonArray_resolves() {
        User u = User.builder().id(10L).departmentId(1L).build();
        when(userRepository.findById(10L)).thenReturn(Optional.of(u));
        when(userRoleRepository.findByUserId(10L)).thenReturn(List.of());
        when(packageRepository.findByUserId(10L)).thenReturn(List.of(pkg(8L)));
        Role role = Role.builder().id(8L).code("REGION")
                .dataScope("CUSTOM").dataRuleIds("[\"1001\",\"1002\"]").build();
        when(roleRepository.findById(8L)).thenReturn(Optional.of(role));

        DataScopeResult r = dataScopeService.resolve(10L);
        assertEquals(DataScopeResult.Scope.CUSTOM, r.scope());
        assertEquals(2, r.deptIds().size());
        assertTrue(r.deptIds().contains(1001L));
        assertTrue(r.deptIds().contains(1002L));
    }

    @Test
    void typeAggregatesAllowedTypes() {
        User u = User.builder().id(10L).departmentId(1L).build();
        when(userRepository.findById(10L)).thenReturn(Optional.of(u));
        when(userRoleRepository.findByUserId(10L)).thenReturn(List.of());
        when(packageRepository.findByUserId(10L)).thenReturn(List.of(pkg(11L), pkg(12L)));
        Role r1 = Role.builder().id(11L).code("A").dataScope("TYPE").dataScopeTypes("[\"VEHICLE\"]").build();
        Role r2 = Role.builder().id(12L).code("B").dataScope("TYPE").dataScopeTypes("[\"BATTERY\",\"DRONE\"]").build();
        when(roleRepository.findById(11L)).thenReturn(Optional.of(r1));
        when(roleRepository.findById(12L)).thenReturn(Optional.of(r2));

        DataScopeResult r = dataScopeService.resolve(10L);
        assertEquals(DataScopeResult.Scope.TYPE, r.scope());
        assertTrue(r.allowedTypes().containsAll(List.of("VEHICLE", "BATTERY", "DRONE")));
    }

    @Test
    void ruleValueResolver_substitutesUserId() {
        User u = User.builder().id(42L).phone("13800000000").departmentId(1L).build();
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(Department.builder().id(1L).orgCode("A01").build()));
        RuleValueResolver resolver = new RuleValueResolver(userRepository, departmentRepository);

        assertEquals("42", resolver.resolve("#{sys_user_id}", 42L));
        assertEquals("A01%", resolver.resolve("#{sys_org_code_like}", 42L));
    }

    @Test
    void ruleValueResolver_rejectsDdlSql() {
        RuleValueResolver resolver = new RuleValueResolver(userRepository, departmentRepository);
        assertThrows(BizException.class, () -> resolver.resolveSql("DROP TABLE claw.users", 1L));
    }

    @Test
    void unauthenticatedDefaultsToSelf() {
        DataScopeResult r = dataScopeService.resolve(null);
        assertEquals(DataScopeResult.Scope.SELF, r.scope());
        assertTrue(r.isSelfOnly());
    }

    private static UserRolePackage pkg(Long roleId) {
        return UserRolePackage.builder().userId(10L).roleId(roleId).build();
    }

    private static UserRole ur(Long roleId) {
        return UserRole.builder().userId(10L).roleId(roleId).build();
    }
}
