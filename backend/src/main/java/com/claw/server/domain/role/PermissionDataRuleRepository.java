package com.claw.server.domain.role;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 数据权限规则仓储（对应 claw.permission_data_rules，V44 新增）。
 */
public interface PermissionDataRuleRepository extends JpaRepository<PermissionDataRule, Long> {

    List<PermissionDataRule> findByPermissionCode(String permissionCode);

    List<PermissionDataRule> findByStatus(String status);
}
