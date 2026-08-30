package com.claw.server.domain.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleTemplatePermissionRepository extends JpaRepository<RoleTemplatePermission, Long> {

    List<RoleTemplatePermission> findByTemplateCode(String templateCode);

    void deleteByTemplateCode(String templateCode);
}
