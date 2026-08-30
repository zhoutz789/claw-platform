package com.claw.server.domain.role;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RoleGroupTemplateRepository extends JpaRepository<RoleGroupTemplate, RoleGroupTemplate.Key> {
    List<RoleGroupTemplate> findByGroupCode(String groupCode);

    Optional<RoleGroupTemplate> findByGroupCodeAndTemplateCode(String groupCode, String templateCode);

    void deleteByGroupCode(String groupCode);
}
