package com.claw.server.domain.role;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long>,
        JpaSpecificationExecutor<RolePermission> {
    List<RolePermission> findByRoleId(Long roleId);

    void deleteByRoleId(Long roleId);

    @Modifying
    @Query("DELETE FROM RolePermission rp WHERE rp.roleId = :roleId AND rp.permissionCode = :code")
    void deleteByRoleIdAndPermissionCode(Long roleId, String code);

    @Modifying
    @Query("DELETE FROM RolePermission rp WHERE rp.permissionCode = :code")
    void deleteByPermissionCode(String code);
}
