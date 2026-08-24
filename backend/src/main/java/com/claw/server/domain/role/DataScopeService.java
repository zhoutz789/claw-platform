package com.claw.server.domain.role;

import com.claw.server.domain.user.User;
import com.claw.server.domain.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 数据范围解析器（C3 遗留迭代）。
 *
 * <p>依据当前用户「已生效角色包」的 data_scope 求并集，取最宽松者生效：
 * <ul>
 *   <li>{@code ALL}        —— 可见全部数据（平台管理员）；</li>
 *   <li>{@code TYPE}       —— 仅可见 data_scope_types 声明的类型（如资产类型）；</li>
 *   <li>{@code DEPARTMENT} —— 仅可见同部门成员名下数据；</li>
 *   <li>{@code SELF}       —— 仅可见本人名下数据（默认）。</li>
 * </ul>
 * 优先级 ALL > TYPE > DEPARTMENT > SELF。多个 TYPE 角色取类型并集。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataScopeService {

    private final UserRolePackageRepository packageRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 生效的数据范围。 */
    public enum EffectiveScope { ALL, TYPE, DEPARTMENT, SELF }

    /** 解析结果：生效范围 + 当前用户部门 + TYPE 模式下允许的类型集合。 */
    public record DataScopeResult(EffectiveScope scope, Long departmentId, Set<String> allowedTypes) {
        public boolean isAll() {
            return scope == EffectiveScope.ALL;
        }

        public boolean isSelfOnly() {
            return scope == EffectiveScope.SELF;
        }
    }

    public DataScopeResult resolve(Long userId) {
        Long deptId = null;
        if (userId != null) {
            deptId = userRepository.findById(userId).map(User::getDepartmentId).orElse(null);
        }
        if (userId == null) {
            return new DataScopeResult(EffectiveScope.SELF, deptId, Set.of());
        }

        List<Role> roles = packageRepository.findByUserId(userId).stream()
                .filter(UserRolePackage::isActive)
                .map(p -> roleRepository.findById(p.getRoleId()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        if (roles.isEmpty()) {
            return new DataScopeResult(EffectiveScope.SELF, deptId, Set.of());
        }

        // ALL 最宽松，直接放行
        if (roles.stream().anyMatch(r -> "ALL".equals(r.getDataScope()))) {
            return new DataScopeResult(EffectiveScope.ALL, deptId, Set.of());
        }

        // TYPE：聚合所有 TYPE 角色的类型并集
        Set<String> types = new HashSet<>();
        for (Role r : roles) {
            if ("TYPE".equals(r.getDataScope())) {
                types.addAll(parseTypes(r.getDataScopeTypes()));
            }
        }
        if (!types.isEmpty()) {
            return new DataScopeResult(EffectiveScope.TYPE, deptId, types);
        }

        // DEPARTMENT：同部门可见
        if (roles.stream().anyMatch(r -> "DEPARTMENT".equals(r.getDataScope()))) {
            return new DataScopeResult(EffectiveScope.DEPARTMENT, deptId, Set.of());
        }

        // 默认 SELF
        return new DataScopeResult(EffectiveScope.SELF, deptId, Set.of());
    }

    private Set<String> parseTypes(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            Collection<String> list = objectMapper.readValue(json, new TypeReference<Collection<String>>() {
            });
            return new HashSet<>(list);
        } catch (Exception e) {
            log.warn("解析 data_scope_types 失败: {}", json, e);
            return Set.of();
        }
    }
}
