package com.claw.server.domain.role;

import com.claw.server.common.enums.RoleSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 角色包授予引擎单元测试（Mockito 模拟仓储，无需 DB）。
 */
@ExtendWith(MockitoExtension.class)
class RoleGrantServiceTest {

    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserRolePackageRepository packageRepository;
    @InjectMocks
    private RoleGrantService service;

    private Role role(String code, boolean auto) {
        return Role.builder().id(1L).code(code).nameI18n("role." + code + ".name")
                .autoGrant(auto).build();
    }

    @Test
    void grantAutoRoles_grants_all_autoRoles() {
        when(roleRepository.findAll()).thenReturn(List.of(
                role("CONSUMER", true), role("PRODUCER", true),
                role("MERCHANT", false)));
        when(packageRepository.findByUserIdAndRoleId(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(packageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.grantAutoRoles(100L);

        verify(packageRepository, times(2)).save(any(UserRolePackage.class));
    }

    @Test
    void apply_is_idempotent_when_active() {
        Role merchant = role("MERCHANT", false);
        UserRolePackage existing = UserRolePackage.builder().userId(100L).roleId(merchant.getId())
                .source(RoleSource.APPLY).build();
        when(roleRepository.findByCode("MERCHANT")).thenReturn(Optional.of(merchant));
        when(packageRepository.findByUserIdAndRoleId(100L, merchant.getId())).thenReturn(Optional.of(existing));

        service.apply(100L, "MERCHANT");

        verify(packageRepository, never()).save(any());
    }

    @Test
    void apply_creates_new_package_when_absent() {
        Role merchant = role("MERCHANT", false);
        when(roleRepository.findByCode("MERCHANT")).thenReturn(Optional.of(merchant));
        when(packageRepository.findByUserIdAndRoleId(100L, merchant.getId())).thenReturn(Optional.empty());
        when(packageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.apply(100L, "MERCHANT");

        verify(packageRepository).save(any(UserRolePackage.class));
    }

    @Test
    void revoke_sets_revokedAt() {
        Role merchant = role("MERCHANT", false);
        UserRolePackage pkg = UserRolePackage.builder().id(9L).userId(100L).roleId(merchant.getId())
                .source(RoleSource.APPLY).grantedAt(Instant.now()).build();
        when(roleRepository.findByCode("MERCHANT")).thenReturn(Optional.of(merchant));
        when(packageRepository.findByUserIdAndRoleId(100L, merchant.getId())).thenReturn(Optional.of(pkg));
        when(packageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.revoke(100L, "MERCHANT");

        assertNotNull(pkg.getRevokedAt());
        verify(packageRepository).save(pkg);
    }

    @Test
    void apply_unknown_role_throws() {
        when(roleRepository.findByCode("GHOST")).thenReturn(Optional.empty());
        assertThrows(Exception.class, () -> service.apply(100L, "GHOST"));
    }
}
