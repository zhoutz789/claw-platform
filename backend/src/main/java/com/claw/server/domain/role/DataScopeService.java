package com.claw.server.domain.role;

import com.claw.server.common.security.DataScopeFieldMapping;
import com.claw.server.common.security.DataScopeResult;
import com.claw.server.common.security.DataScopeSpec;
import com.claw.server.domain.user.Department;
import com.claw.server.domain.user.DepartmentRepository;
import com.claw.server.domain.user.User;
import com.claw.server.domain.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 数据范围解析器（权限通电 P1-T03 增强）。
 *
 * <p>依据当前用户「已生效角色包（user_role_packages）+ 平台固定角色（user_roles）」求并集，取最宽松者生效：
 * <ul>
 *   <li>{@code ALL}                 —— 可见全部数据（超级管理员或持有 * 权限位）；</li>
 *   <li>{@code TYPE}               —— 仅可见各 TYPE 角色声明的类型并集；</li>
 *   <li>{@code DEPARTMENT}         —— 仅可见同部门；</li>
 *   <li>{@code DEPARTMENT_AND_BELOW}—— 仅可见本部门及以下（主部门 org_code 前缀 LIKE）；</li>
 *   <li>{@code CUSTOM}             —— 仅可见角色 data_rule_ids 声明的自定义部门集合；</li>
 *   <li>{@code SELF}               —— 仅可见本人名下（默认）。</li>
 * </ul>
 * 优先级 ALL > DEPARTMENT_AND_BELOW > CUSTOM > DEPARTMENT > TYPE > SELF。
 *
 * <p>向后兼容：角色未显式配置 data_scope（null/空）时行为等同今天的默认 SELF；未接入 @DataScope 的域完全不变。
 * 同时读取绑定到用户生效角色的数据规则（PermissionDataRule），对 rule_value 中的 #{...} 变量由
 * {@link RuleValueResolver} 运行时替换（SQL 模式强制白名单校验）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataScopeService {

    private final UserRolePackageRepository packageRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final DepartmentRepository departmentRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionDataRuleRepository dataRuleRepository;
    private final RuleValueResolver ruleValueResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 解析当前用户的数据范围。 */
    public DataScopeResult resolve(Long userId) {
        if (userId == null) {
            return selfOnly(null);
        }
        User user = userRepository.findById(userId).orElse(null);
        Long deptId = user == null ? null : user.getDepartmentId();
        String orgCode = resolveOrgCode(deptId);

        Set<Long> roleIds = new HashSet<>();
        userRoleRepository.findByUserId(userId).forEach(ur -> roleIds.add(ur.getRoleId()));
        packageRepository.findByUserId(userId).stream()
                .filter(UserRolePackage::isActive)
                .forEach(p -> roleIds.add(p.getRoleId()));
        if (roleIds.isEmpty()) {
            return selfOnly(userId, deptId);
        }

        boolean superAdmin = false;
        DataScopeResult.Scope merged = DataScopeResult.Scope.SELF;
        Set<String> types = new LinkedHashSet<>();
        Set<Long> customDepts = new LinkedHashSet<>();

        for (Long roleId : roleIds) {
            Role role = roleRepository.findById(roleId).orElse(null);
            if (role == null) {
                continue;
            }
            if (isSuperAdmin(role)) {
                superAdmin = true;
            }
            String ds = role.getDataScope();
            if (ds == null || ds.isBlank()) {
                ds = "SELF";
            }
            merged = widen(merged, mapScope(ds));
            if ("TYPE".equals(ds)) {
                types.addAll(parseTypes(role.getDataScopeTypes()));
            }
            if ("CUSTOM".equals(ds)) {
                customDepts.addAll(parseDeptIds(role.getDataRuleIds()));
                customDepts.addAll(loadRuleDeptIds(role.getId(), userId));
            }
        }

        if (superAdmin) {
            return build(DataScopeResult.Scope.ALL, deptId, types, customDepts, orgCode, userId);
        }
        return build(merged, deptId, types, customDepts, orgCode, userId);
    }

    /** 翻译为 JPA Specification（字段映射由调用方提供）。 */
    public <T> Specification<T> toSpecification(DataScopeResult result, DataScopeFieldMapping mapping) {
        return DataScopeSpec.of(mapping).apply(result);
    }

    private String resolveOrgCode(Long deptId) {
        if (deptId == null) {
            return null;
        }
        return departmentRepository.findById(deptId).map(Department::getOrgCode).orElse(null);
    }

    private boolean isSuperAdmin(Role role) {
        if ("SUPER_ADMIN".equals(role.getCode())) {
            return true;
        }
        return parseGrants(role.getGrants()).contains("*");
    }

    /**
     * 从角色绑定的数据规则（role_permissions.data_rule_ids → permission_data_rules）解析出 CUSTOM 部门集合。
     * 仅聚合「作用列为 department_id 且条件为 IN」的规则，rule_value 经 {@link RuleValueResolver} 运行时替换变量。
     */
    private Set<Long> loadRuleDeptIds(Long roleId, Long userId) {
        Set<Long> ids = new LinkedHashSet<>();
        rolePermissionRepository.findByRoleId(roleId).forEach(rp -> {
            if (rp.getDataRuleIds() == null || rp.getDataRuleIds().isBlank()) {
                return;
            }
            for (String tok : rp.getDataRuleIds().split(",")) {
                try {
                    Long ruleId = Long.parseLong(tok.trim());
                    dataRuleRepository.findById(ruleId).ifPresent(rule -> {
                        if ("department_id".equalsIgnoreCase(rule.getRuleColumn())
                                && "IN".equalsIgnoreCase(rule.getRuleConditions())) {
                            String val = ruleValueResolver.resolve(rule.getRuleValue(), userId);
                            for (String v : val.split(",")) {
                                try {
                                    ids.add(Long.parseLong(v.trim()));
                                } catch (NumberFormatException ignored) {
                                    // 变量替换后非数字片段跳过
                                }
                            }
                        }
                    });
                } catch (NumberFormatException ignored) {
                    // 非数字片段（如权限点 code）跳过
                }
            }
        });
        return ids;
    }

    /**
     * 解析角色 CUSTOM 数据范围里的部门 id 集合。
     * 兼容两种入参格式：JSON 数组（前端 {@code ["1001","1002"]}）与逗号分隔（{@code 1001,1002}），
     * 以免前后端约定不一致时静默解析为空集。
     */
    private Set<Long> parseDeptIds(String raw) {
        Set<Long> ids = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        String normalized = raw.trim();
        if (normalized.startsWith("[")) {
            try {
                Collection<String> list = objectMapper.readValue(normalized,
                        new TypeReference<Collection<String>>() {
                        });
                for (String s : list) {
                    try {
                        ids.add(Long.parseLong(s.trim()));
                    } catch (NumberFormatException ignored) {
                        // 忽略非数字片段
                    }
                }
                return ids;
            } catch (Exception e) {
                log.warn("解析 data_rule_ids(JSON) 失败: {}", raw, e);
                // 落入逗号分隔兜底分支
            }
        }
        for (String s : normalized.split(",")) {
            try {
                ids.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
                // 忽略非数字
            }
        }
        return ids;
    }

    private Set<String> parseTypes(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            Collection<String> list = objectMapper.readValue(json, new TypeReference<Collection<String>>() {
            });
            return new LinkedHashSet<>(list);
        } catch (Exception e) {
            log.warn("解析 data_scope_types 失败: {}", json, e);
            return Set.of();
        }
    }

    private Set<String> parseGrants(String grantsJson) {
        if (grantsJson == null || grantsJson.isBlank()
                || "{}".equals(grantsJson.trim()) || "[]".equals(grantsJson.trim())) {
            return Set.of();
        }
        try {
            List<String> list = objectMapper.readValue(grantsJson, new TypeReference<List<String>>() {
            });
            return new HashSet<>(list);
        } catch (Exception e) {
            log.debug("解析 roles.grants 失败：{}", grantsJson, e);
            return Set.of();
        }
    }

    private static DataScopeResult.Scope mapScope(String ds) {
        return switch (ds) {
            case "ALL" -> DataScopeResult.Scope.ALL;
            case "DEPARTMENT_AND_BELOW" -> DataScopeResult.Scope.DEPARTMENT_AND_BELOW;
            case "CUSTOM" -> DataScopeResult.Scope.CUSTOM;
            case "DEPARTMENT" -> DataScopeResult.Scope.DEPARTMENT;
            case "TYPE" -> DataScopeResult.Scope.TYPE;
            default -> DataScopeResult.Scope.SELF;
        };
    }

    private static DataScopeResult.Scope widen(DataScopeResult.Scope a, DataScopeResult.Scope b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(DataScopeResult.Scope s) {
        return switch (s) {
            case ALL -> 5;
            case DEPARTMENT_AND_BELOW -> 4;
            case CUSTOM -> 3;
            case DEPARTMENT -> 2;
            case TYPE -> 1;
            case SELF -> 0;
        };
    }

    private static Set<String> orgCodePrefixes(String orgCode) {
        return (orgCode == null || orgCode.isBlank()) ? Set.of() : Set.of(orgCode);
    }

    private static DataScopeResult build(DataScopeResult.Scope scope, Long deptId, Set<String> types,
                                         Set<Long> customDepts, String orgCode, Long userId) {
        return new DataScopeResult(scope, deptId, Set.copyOf(types),
                Set.copyOf(customDepts), orgCodePrefixes(orgCode), userId);
    }

    private static DataScopeResult selfOnly(Long userId, Long deptId) {
        return new DataScopeResult(DataScopeResult.Scope.SELF, deptId, Set.of(), Set.of(), Set.of(), userId);
    }

    private static DataScopeResult selfOnly(Long userId) {
        return selfOnly(userId, null);
    }
}
