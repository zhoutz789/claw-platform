package com.claw.server.domain.role;

import com.claw.server.common.api.BizException;
import com.claw.server.common.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色模板服务（增量 A）。
 *
 * <p>角色模板是「业务视角权限打包」的元数据（引用既有 permissions 原子码子集）。
 * 授予时回写对应角色（roles.code == template.code）的 grants JSON（权限真源，见 V47 注释），
 * 使模板展开后的权限集合生效到账号。模板码与角色码一一对应：
 * MANUFACTURER / STATION / CUSTOMER / PLATFORM_ADMIN。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleTemplateService {

    private final RoleTemplateRepository templateRepository;
    private final RoleTemplatePermissionRepository templatePermissionRepository;
    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;
    private final PermissionService permissionService;

    @Transactional(readOnly = true)
    public List<RoleTemplate> listTemplates() {
        return templateRepository.findAll();
    }

    @Transactional(readOnly = true)
    public RoleTemplate getTemplate(String code) {
        return templateRepository.findByCode(code)
                .orElseThrow(() -> BizException.of(40401, "role.template.not.found"));
    }

    @Transactional
    public RoleTemplate createTemplate(String code, String name, String principalType, String description) {
        if (templateRepository.findByCode(code).isPresent()) {
            throw BizException.of(40901, "role.template.code.exists");
        }
        RoleTemplate t = RoleTemplate.builder()
                .code(code)
                .name(name)
                .principalType(principalType)
                .description(description)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return templateRepository.save(t);
    }

    @Transactional
    public RoleTemplate updateTemplate(String code, String name, String description) {
        RoleTemplate t = getTemplate(code);
        if (name != null) {
            t.setName(name);
        }
        if (description != null) {
            t.setDescription(description);
        }
        t.setUpdatedAt(Instant.now());
        return templateRepository.save(t);
    }

    @Transactional(readOnly = true)
    public List<String> getPermissions(String code) {
        return templatePermissionRepository.findByTemplateCode(code).stream()
                .map(RoleTemplatePermission::getPermissionCode)
                .toList();
    }

    /**
     * 设置模板权限集合（全量覆盖）。
     * 同时回写对应角色（roles.code == template.code）的 grants JSON，使权限立即生效。
     */
    @Transactional
    public void setPermissions(String code, List<String> permissionCodes) {
        RoleTemplate t = getTemplate(code);
        // 清理旧关系
        templatePermissionRepository.findByTemplateCode(code)
                .forEach(templatePermissionRepository::delete);
        Set<String> unique = new HashSet<>(permissionCodes);
        for (String pc : unique) {
            if (permissionRepository.findByCode(pc).isEmpty()) {
                throw BizException.of(40401, "permission.not.found");
            }
            templatePermissionRepository.save(RoleTemplatePermission.builder()
                    .templateCode(code)
                    .permissionCode(pc)
                    .build());
        }
        // 回写 roles.grants（权限真源）
        roleRepository.findByCode(code).ifPresent(role -> {
            role.setGrants(permissionService.toGrantsJson(unique));
            roleRepository.save(role);
            permissionService.evictByRole(role.getId());
        });
        log.info("角色模板 {} 权限已更新并回写角色 grants，共 {} 项", code, unique.size());
    }

    /** 展开模板为权限码集合（供角色组 / 主体授予聚合使用）。 */
    @Transactional(readOnly = true)
    public Set<String> expandPermissions(String code) {
        return new HashSet<>(getPermissions(code));
    }
}
