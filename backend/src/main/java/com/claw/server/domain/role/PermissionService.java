package com.claw.server.domain.role;

import com.claw.server.common.enums.AclRelation;
import com.claw.server.domain.asset.UserAssetsAclRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 三层权限校验（技术文档 4.5）：RBAC（平台角色）+ 角色包（user_role_packages）+ 资产 ACL。
 * 任一命中即放行。权限位形如 {@code "asset:create"} / {@code "swap:order"}，存于 roles.grants（JSON 数组）。
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final UserRoleRepository userRoleRepository;
    private final UserRolePackageRepository packageRepository;
    private final UserAssetsAclRepository aclRepository;
    private final RoleRepository roleRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 用户级权限：RBAC 角色或角色包任一授予该权限位即放行。 */
    public boolean hasUserPermission(Long userId, String permission) {
        Set<String> granted = new HashSet<>();
        // RBAC 层
        userRoleRepository.findByUserId(userId).forEach(ur ->
                roleRepository.findById(ur.getRoleId()).ifPresent(r -> granted.addAll(parseGrants(r.getGrants()))));
        // 角色包层（仅生效中）
        packageRepository.findByUserId(userId).stream()
                .filter(UserRolePackage::isActive)
                .forEach(pkg -> roleRepository.findById(pkg.getRoleId())
                        .ifPresent(r -> granted.addAll(parseGrants(r.getGrants()))));
        return granted.contains(permission);
    }

    /** 资产级权限：用户对某资产是否具备指定关系（MANAGE/USE/LEASE）。 */
    public boolean hasAssetPermission(Long userId, Long assetId, AclRelation relation) {
        return aclRepository.existsByUserIdAndAssetIdAndRelation(userId, assetId, relation);
    }

    /** 平台管理员判定（RBAC 平台角色）。 */
    public boolean isPlatformAdmin(Long userId) {
        return userRoleRepository.findByUserId(userId).stream()
                .anyMatch(ur -> roleRepository.findById(ur.getRoleId())
                        .map(r -> "PLATFORM_ADMIN".equals(r.getCode())).orElse(false));
    }

    private Set<String> parseGrants(String grantsJson) {
        if (grantsJson == null || grantsJson.isBlank() || "{}".equals(grantsJson.trim())) {
            return Set.of();
        }
        try {
            List<String> list = objectMapper.readValue(grantsJson, new TypeReference<List<String>>() {});
            return new HashSet<>(list);
        } catch (Exception e) {
            return Set.of();
        }
    }
}
