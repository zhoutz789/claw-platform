package com.claw.server.domain.role;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface PermissionRepository extends JpaRepository<Permission, Long>,
        JpaSpecificationExecutor<Permission> {
    List<Permission> findByParentCodeOrderBySortNoAsc(String parentCode);
    List<Permission> findByPtypeOrderBySortNoAsc(String ptype);
    java.util.Optional<Permission> findByCode(String code);
}
