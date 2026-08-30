package com.claw.server.domain.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleGroupTemplateRepository extends JpaRepository<RoleGroupTemplate, Long> {

    List<RoleGroupTemplate> findByGroupCode(String groupCode);

    void deleteByGroupCode(String groupCode);
}
