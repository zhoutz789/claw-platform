package com.claw.server.domain.role;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RoleTemplatePermissionRepository extends JpaRepository<RoleTemplatePermission, RoleTemplatePermission.Key> {
    List<RoleTemplatePermission> findByTemplateCode(String templateCode);

    Optional<RoleTemplatePermission> findByTemplateCodeAndPermissionCode(String templateCode, String permissionCode);

    void deleteByTemplateCode(String templateCode);
}
