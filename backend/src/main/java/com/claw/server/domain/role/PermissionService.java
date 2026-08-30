package com.claw.server.domain.role;

import com.claw.server.common.enums.AclRelation;
import com.claw.server.domain.asset.UserAssetsAclRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 三层权限聚合与校验（技术文档 4.5）：RBAC（平台角色）+ 角色包（user_role_packages）+ 资产 ACL。
 * 任一命中即放行。权限位形如 {@code "asset:create"} / {@code "swap:order"}，存于 roles.grants（JSON 数组）。
 *
 * <p>权限通电内核（P1-T02）核心：
 * <ul>
 *   <li>{@link #effectivePermissions(Long)} 聚合用户全部生效角色的 grants 为去重集合，供切面 / 前端统一消费；</li>
 *   <li>Redis 缓存 {@code perm:{userId} -> Set<code>}，TTL 300s，命中即返回，避免每次请求回查 DB；</li>
 *   <li><b>Redis 降级是必需项</b>：缓存读 / 写异常（如本地 Redis 未起 Connection refused）一律 try/catch，
 *       静默回退 DB 计算，绝不因缓存层故障而拒绝所有请求；</li>
 *   <li>角色 / 权限 / 用户角色变更时主动 {@link #evictUser(Long)} / {@link #evictByRole(Long)} 清缓存，
 *       保证「改权限后 5s 内生效」。</li>
 * </ul>
 *
 * <p><b>增量 C 扩展（O30）</b>：第四层并入<b>子账号授权集合</b> ——
 * <pre>
 * effective(user) = RBAC角色 ∪ 角色包 ∪ subAccountGrants(user)
 * subAccountGrants(user):
 *     ALL     → 主账号角色模板全量（未来新功能自动继承）
 *     PARTIAL → grant_items ∩ 主账号模板集合（交集防越权）
 * </pre>
 * 子账号在 {@code principal_bindings} 里没有行，故此处按 {@code sub_accounts.user_id} 回溯。
 * 依赖只取仓储（不引 SubAccountGrantService），避免 role ↔ subaccount 的 bean 循环依赖。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionService {

    private final UserRoleRepository userRoleRepository;
    private final UserRolePackageRepository packageRepository;
    private final UserAssetsAclRepository aclRepository;
    private final RoleRepository roleRepository;
    /** 增量 C：子账号授权相关仓储（只读，用于第四层权限合并）。 */
    private final com.claw.server.domain.subaccount.SubAccountRepository subAccountRepository;
    private final com.claw.server.domain.subaccount.SubAccountGrantRepository subAccountGrantRepository;
    private final com.claw.server.domain.subaccount.SubAccountGrantItemRepository subAccountGrantItemRepository;
    private final RoleTemplatePermissionRepository templatePermissionRepository;
    /** Redis 可选：本地未配置 / 不可达时保持 null，全程降级直查 DB。 */
    private final StringRedisTemplate redisTemplate;
    /** Spring Boot 自动装配的 ObjectMapper 实例。 */
    private final ObjectMapper objectMapper;

    private static final String CACHE_PREFIX = "perm:";
    private static final Duration CACHE_TTL = Duration.ofSeconds(300);
    /** 超级管理员通配符：持有即拥有全部权限位。 */
    public static final String WILDCARD = "*";

    /**
     * 计算并缓存用户的「有效权限位集合」（三层合并：RBAC 角色 + 角色包）。
     * 缓存优先；缓存缺失或 Redis 异常时回退 DB 计算并尽力回写。
     */
    public Set<String> effectivePermissions(Long userId) {
        Set<String> cached = readCache(userId);
        if (cached != null) {
            return cached;
        }
        Set<String> computed = computeEffective(userId);
        writeCache(userId, computed);
        return computed;
    }

    /** 四层合并：RBAC 角色 grants + 生效角色包 grants + 子账号授权，去重。 */
    private Set<String> computeEffective(Long userId) {
        Set<String> granted = new HashSet<>();
        // 收集「直接授予」的角色 id（RBAC + 生效中的角色包），再去重沿 parent_id 向上合并祖先 grants。
        Set<Long> directRoleIds = new HashSet<>();
        userRoleRepository.findByUserId(userId)
                .forEach(ur -> directRoleIds.add(ur.getRoleId()));
        packageRepository.findByUserId(userId).stream()
                .filter(UserRolePackage::isActive)
                .forEach(pkg -> directRoleIds.add(pkg.getRoleId()));
        // 祖先合并（角色继承，周老板默认决策 #9）：visited 集合 + 深度上限防环。
        Set<Long> visited = new HashSet<>();
        for (Long roleId : directRoleIds) {
            mergeAncestorGrants(roleId, granted, visited, 0);
        }
        // 第四层：子账号授权（O30）
        granted.addAll(computeSubAccountGrants(userId));
        return granted;
    }

    /**
     * 子账号授权集合（增量 C · O30）。
     *
     * @param userId 登录用户 ID（可能是子账号）
     * @return 授权权限码集合；非子账号或无授权时返回空集合
     */
    private Set<String> computeSubAccountGrants(Long userId) {
        if (userId == null || subAccountRepository == null) {
            return Set.of();
        }
        try {
            var sa = subAccountRepository.findTopByUserIdAndStatus(userId, "ACTIVE");
            if (sa.isEmpty() || !sa.get().isActive()) {
                return Set.of();
            }
            var grant = subAccountGrantRepository.findBySubAccountIdAndStatus(sa.get().getId(), "ACTIVE");
            if (grant.isEmpty()) {
                return Set.of();
            }
            // 主账号模板集合（越权判定的上界）
            Set<String> ownerPerms = new HashSet<>();
            for (RoleTemplatePermission tp
                    : templatePermissionRepository.findByTemplateCode(sa.get().getOwnerPrincipalType())) {
                ownerPerms.add(tp.getPermissionCode());
            }
            if (grant.get().isAll()) {
                // ALL：跟随模板，不落明细 —— 平台新增功能自动继承
                return ownerPerms;
            }
            // PARTIAL：明细 ∩ 主账号模板（交集防越权）
            Set<String> out = new HashSet<>();
            for (var item : subAccountGrantItemRepository.findByGrantId(grant.get().getId())) {
                if (ownerPerms.contains(item.getPermissionCode())) {
                    out.add(item.getPermissionCode());
                }
            }
            return out;
        } catch (Exception e) {
            // 子账号授权解析失败不应吃掉主体已有的 RBAC 权限，降级为空集合
            log.warn("子账号授权集合计算失败，按无授权降级：userId={}", userId, e);
            return Set.of();
        }
    }

    /**
     * 合并某角色自身及其全部祖先角色的 grants（角色继承）。
     * 递归沿 parent_id 向上；用 visited 集合去重并防止成环，深度上限 16 作兜底。
     *
     * @param roleId  当前角色 id（null 直接返回）
     * @param granted 输出集合（累加 grants）
     * @param visited 已处理角色 id 集合（防环 / 去重）
     * @param depth   递归深度（从 0 起，超过 16 即终止）
     */
    private void mergeAncestorGrants(Long roleId, Set<String> granted, Set<Long> visited, int depth) {
        if (roleId == null || visited.contains(roleId) || depth > 16) {
            return;
        }
        visited.add(roleId);
        roleRepository.findById(roleId).ifPresent(r -> {
            granted.addAll(parseGrants(r.getGrants()));
            mergeAncestorGrants(r.getParentId(), granted, visited, depth + 1);
        });
    }

    /** 用户级权限：RBAC 角色或角色包任一授予该权限位即放行（兼容 WILDCARD）。 */
    public boolean hasUserPermission(Long userId, String permission) {
        Set<String> granted = effectivePermissions(userId);
        return granted.contains(permission) || granted.contains(WILDCARD);
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

    // ---------------------- 缓存：perm:{userId} -> Set<code> ----------------------

    private Set<String> readCache(Long userId) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            Set<String> members = redisTemplate.opsForSet().members(CACHE_PREFIX + userId);
            if (members == null || members.isEmpty()) {
                return null;
            }
            // 拷贝为可变集合，避免外部改动 Redis 返回的只读视图
            return new HashSet<>(members);
        } catch (Exception e) {
            // Redis 不可用（Connection refused 等）：降级直查 DB，不影响业务。
            log.warn("读取权限缓存失败，降级直查 DB：userId={}", userId, e);
            return null;
        }
    }

    private void writeCache(Long userId, Set<String> perms) {
        if (redisTemplate == null || perms == null) {
            return;
        }
        try {
            String key = CACHE_PREFIX + userId;
            redisTemplate.delete(key);
            if (!perms.isEmpty()) {
                redisTemplate.opsForSet().add(key, perms.toArray(new String[0]));
            }
            redisTemplate.expire(key, CACHE_TTL);
        } catch (Exception e) {
            // 回写失败不阻断主流程，下次请求仍会回源 DB 重建缓存。
            log.warn("回写权限缓存失败，忽略：userId={}", userId, e);
        }
    }

    /** 主动清除某用户的权限缓存（角色 / 权限变更后调用）。 */
    public void evictUser(Long userId) {
        if (redisTemplate == null || userId == null) {
            return;
        }
        try {
            redisTemplate.delete(CACHE_PREFIX + userId);
        } catch (Exception e) {
            log.warn("清除权限缓存失败，忽略：userId={}", userId, e);
        }
    }

    /** 主动清除「拥有某角色的全部用户」的权限缓存（角色 grants 变更后调用）。 */
    public void evictByRole(Long roleId) {
        if (redisTemplate == null || roleId == null) {
            return;
        }
        userRoleRepository.findByRoleId(roleId).forEach(ur -> evictUser(ur.getUserId()));
        packageRepository.findByRoleId(roleId).forEach(p -> evictUser(p.getUserId()));
    }

    // ---------------------- grants 解析（兼容历史数据） ----------------------

    /** 解析 roles.grants（JSON 数组字符串）为权限位集合；兼容 {} / [] / 空 / 畸形。 */
    private Set<String> parseGrants(String grantsJson) {
        if (!StringUtils.hasText(grantsJson) || "{}".equals(grantsJson.trim()) || "[]".equals(grantsJson.trim())) {
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

    /** 防御性解析（供角色 grants 写入前的校验 / 归一化使用）。 */
    public Set<String> parseGrantsPublic(String grantsJson) {
        return parseGrants(grantsJson);
    }

    /** 集合转 JSON 数组字符串（供角色 grants 写入使用）。 */
    public String toGrantsJson(Collection<String> perms) {
        try {
            return objectMapper.writeValueAsString(Objects.requireNonNullElseGet(perms, HashSet::new));
        } catch (Exception e) {
            log.warn("序列化 grants 失败", e);
            return "[]";
        }
    }
}
