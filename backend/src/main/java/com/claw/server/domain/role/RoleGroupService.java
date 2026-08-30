package com.claw.server.domain.role;

import com.claw.server.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色组服务（增量 A）。
 *
 * <p>角色组聚合多个角色模板（能力包），作为「系统管理第 6 项能力载体」。
 * 授予角色组即展开其下全部模板的权限集合。本服务聚焦模板成员关系维护；
 * 实际授予账号由 {@link PrincipalBindingService} 绑定业务主体时叠加对应模板权限。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleGroupService {

    private final RoleGroupRepository groupRepository;
    private final RoleGroupTemplateRepository groupTemplateRepository;
    private final RoleTemplateRepository templateRepository;
    private final RoleTemplateService templateService;

    @Transactional(readOnly = true)
    public List<RoleGroup> listGroups() {
        return groupRepository.findAll();
    }

    @Transactional(readOnly = true)
    public RoleGroup getGroup(String code) {
        return groupRepository.findByCode(code)
                .orElseThrow(() -> BizException.of(40401, "role.group.not.found"));
    }

    @Transactional
    public RoleGroup createGroup(String code, String name, String description, Long createdBy) {
        if (groupRepository.findByCode(code).isPresent()) {
            throw BizException.of(40901, "role.group.code.exists");
        }
        RoleGroup g = RoleGroup.builder()
                .code(code)
                .name(name)
                .description(description)
                .createdBy(createdBy)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return groupRepository.save(g);
    }

    @Transactional
    public RoleGroup updateGroup(String code, String name, String description) {
        RoleGroup g = getGroup(code);
        if (name != null) {
            g.setName(name);
        }
        if (description != null) {
            g.setDescription(description);
        }
        g.setUpdatedAt(Instant.now());
        return groupRepository.save(g);
    }

    @Transactional
    public void deleteGroup(String code) {
        RoleGroup g = getGroup(code);
        groupTemplateRepository.findByGroupCode(code).forEach(groupTemplateRepository::delete);
        groupRepository.delete(g);
    }

    @Transactional(readOnly = true)
    public List<String> listTemplates(String groupCode) {
        getGroup(groupCode);
        return groupTemplateRepository.findByGroupCode(groupCode).stream()
                .map(RoleGroupTemplate::getTemplateCode)
                .toList();
    }

    @Transactional
    public void addTemplate(String groupCode, String templateCode) {
        getGroup(groupCode);
        if (templateRepository.findByCode(templateCode).isEmpty()) {
            throw BizException.of(40401, "role.template.not.found");
        }
        if (groupTemplateRepository.findByGroupCodeAndTemplateCode(groupCode, templateCode).isEmpty()) {
            groupTemplateRepository.save(RoleGroupTemplate.builder()
                    .groupCode(groupCode)
                    .templateCode(templateCode)
                    .build());
        }
    }

    @Transactional
    public void removeTemplate(String groupCode, String templateCode) {
        groupTemplateRepository.findByGroupCodeAndTemplateCode(groupCode, templateCode)
                .ifPresent(groupTemplateRepository::delete);
    }

    /** 展开角色组为权限码集合（所有成员模板权限的并集）。 */
    @Transactional(readOnly = true)
    public Set<String> expandPermissions(String groupCode) {
        Set<String> perms = new HashSet<>();
        for (String tpl : listTemplates(groupCode)) {
            perms.addAll(templateService.expandPermissions(tpl));
        }
        return perms;
    }
}
