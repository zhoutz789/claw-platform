package com.claw.server.domain.role;

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

@ExtendWith(MockitoExtension.class)
class DataScopeServiceTest {

    @Mock
    private UserRolePackageRepository packageRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private DataScopeService dataScopeService;

    @Test
    void allRoleWinsOverSelf() {
        User u = User.builder().id(10L).departmentId(1L).build();
        when(userRepository.findById(10L)).thenReturn(Optional.of(u));
        Role all = Role.builder().code("SUPER_ADMIN").dataScope("ALL").build();
        when(packageRepository.findByUserId(10L)).thenReturn(List.of(pkg(1L)));
        when(roleRepository.findById(1L)).thenReturn(Optional.of(all));

        DataScopeService.DataScopeResult r = dataScopeService.resolve(10L);
        assertEquals(DataScopeService.EffectiveScope.ALL, r.scope());
        assertTrue(r.isAll());
    }

    @Test
    void selfScopeWhenOnlySelfRole() {
        User u = User.builder().id(10L).departmentId(1L).build();
        when(userRepository.findById(10L)).thenReturn(Optional.of(u));
        Role self = Role.builder().code("CUSTOMER").dataScope("SELF").build();
        when(packageRepository.findByUserId(10L)).thenReturn(List.of(pkg(2L)));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(self));

        DataScopeService.DataScopeResult r = dataScopeService.resolve(10L);
        assertEquals(DataScopeService.EffectiveScope.SELF, r.scope());
        assertTrue(r.isSelfOnly());
    }

    @Test
    void departmentScopeCarriesDeptId() {
        User u = User.builder().id(10L).departmentId(7L).build();
        when(userRepository.findById(10L)).thenReturn(Optional.of(u));
        Role dept = Role.builder().code("OPERATOR").dataScope("DEPARTMENT").build();
        when(packageRepository.findByUserId(10L)).thenReturn(List.of(pkg(3L)));
        when(roleRepository.findById(3L)).thenReturn(Optional.of(dept));

        DataScopeService.DataScopeResult r = dataScopeService.resolve(10L);
        assertEquals(DataScopeService.EffectiveScope.DEPARTMENT, r.scope());
        assertEquals(7L, r.departmentId());
    }

    @Test
    void typeScopeAggregatesAllowedTypes() {
        User u = User.builder().id(10L).departmentId(1L).build();
        when(userRepository.findById(10L)).thenReturn(Optional.of(u));
        Role type = Role.builder().code("ASSET_VIEWER")
                .dataScope("TYPE").dataScopeTypes("[\"VEHICLE\",\"BATTERY\"]").build();
        when(packageRepository.findByUserId(10L)).thenReturn(List.of(pkg(4L)));
        when(roleRepository.findById(4L)).thenReturn(Optional.of(type));

        DataScopeService.DataScopeResult r = dataScopeService.resolve(10L);
        assertEquals(DataScopeService.EffectiveScope.TYPE, r.scope());
        assertTrue(r.allowedTypes().contains("VEHICLE"));
        assertTrue(r.allowedTypes().contains("BATTERY"));
    }

    @Test
    void unauthenticatedDefaultsToSelf() {
        DataScopeService.DataScopeResult r = dataScopeService.resolve(null);
        assertEquals(DataScopeService.EffectiveScope.SELF, r.scope());
    }

    private static UserRolePackage pkg(Long roleId) {
        return UserRolePackage.builder().userId(10L).roleId(roleId).build();
    }
}
