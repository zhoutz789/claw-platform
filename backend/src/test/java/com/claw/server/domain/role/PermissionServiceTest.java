package com.claw.server.domain.role;

import com.claw.server.domain.asset.UserAssetsAclRepository;
import com.claw.server.domain.subaccount.SubAccountGrantItemRepository;
import com.claw.server.domain.subaccount.SubAccountGrantRepository;
import com.claw.server.domain.subaccount.SubAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PermissionService 合并算法 + Redis 降级单元测试（P1-T02 验收要求：至少 2 个单测）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>三层合并（RBAC 角色 + 角色包）去重并集，且兼容历史 grants（{} / [] / null / 畸形）；</li>
 *   <li>通配符 "*" 视为超级管理员；</li>
 *   <li>Redis 读失败（Connection refused 等）时静默回退 DB 计算，绝不拒绝请求；</li>
 *   <li>缓存命中直接返回、变更后主动 evict。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private UserRolePackageRepository packageRepository;
    @Mock
    private UserAssetsAclRepository aclRepository;
    @Mock
    private RoleRepository roleRepository;
    /** 增量 C：子账号授权相关仓储（本测试不涉及子账号，故不 stub，返回空即合并为空集）。 */
    @Mock
    private SubAccountRepository subAccountRepository;
    @Mock
    private SubAccountGrantRepository subAccountGrantRepository;
    @Mock
    private SubAccountGrantItemRepository subAccountGrantItemRepository;
    @Mock
    private RoleTemplatePermissionRepository templatePermissionRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOperations;

    private PermissionService newService() {
        PermissionService svc = new PermissionService(userRoleRepository, packageRepository, aclRepository,
                roleRepository, subAccountRepository, subAccountGrantRepository,
                subAccountGrantItemRepository, templatePermissionRepository,
                new ObjectMapper());
        // StringRedisTemplate 主代码已改为可选字段注入（@Autowired(required=false)），
        // 此处用反射注入 mock，以验证 Redis 降级 / 缓存命中 / evict 行为。
        ReflectionTestUtils.setField(svc, "redisTemplate", redisTemplate);
        return svc;
    }

    private Role role(Long id, String grants) {
        return Role.builder().id(id).code("R" + id).grants(grants).build();
    }

    private UserRole userRole(Long roleId) {
        return UserRole.builder().userId(1L).roleId(roleId).build();
    }

    private UserRolePackage pkg(Long roleId) {
        return UserRolePackage.builder().userId(1L).roleId(roleId).build(); // revokedAt=null => active
    }

    // ---------- 1) 三层合并 + 去重 ----------
    @Test
    void effectivePermissions_mergesRbacAndPackages_withDedup() {
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of(userRole(10L)));
        when(packageRepository.findByUserId(1L)).thenReturn(List.of(pkg(20L)));
        // RBAC 角色 grants
        when(roleRepository.findById(10L)).thenReturn(java.util.Optional.of(
                role(10L, "[\"asset:create\",\"asset:update\"]")));
        // 角色包 grants（含与 RBAC 重复的 asset:update，验证去重）
        when(roleRepository.findById(20L)).thenReturn(java.util.Optional.of(
                role(20L, "[\"asset:update\",\"asset:delete\",\"asset:export\"]")));

        Set<String> perms = newService().effectivePermissions(1L);

        assertEquals(Set.of("asset:create", "asset:update", "asset:delete", "asset:export"), perms);
    }

    // ---------- 2) 历史 grants 兼容 ----------
    @Test
    void effectivePermissions_toleratesLegacyGrants() {
        when(userRoleRepository.findByUserId(2L)).thenReturn(
                List.of(userRole(11L), userRole(12L), userRole(13L)));
        when(packageRepository.findByUserId(2L)).thenReturn(List.of());
        // 一个角色 grants=null，另一个 grants="{}"，还有一个畸形 JSON —— 均不应抛异常
        when(roleRepository.findById(11L)).thenReturn(java.util.Optional.of(role(11L, null)));
        when(roleRepository.findById(12L)).thenReturn(java.util.Optional.of(role(12L, "{}")));
        when(roleRepository.findById(13L)).thenReturn(java.util.Optional.of(role(13L, "not-json")));

        Set<String> perms = newService().effectivePermissions(2L);
        assertTrue(perms.isEmpty(), "历史/畸形 grants 应被安全忽略，不抛异常");
    }

    // ---------- 3) 通配符 ----------
    @Test
    void wildcard_grantsEverything() {
        when(userRoleRepository.findByUserId(3L)).thenReturn(List.of(userRole(14L)));
        when(packageRepository.findByUserId(3L)).thenReturn(List.of());
        when(roleRepository.findById(14L)).thenReturn(java.util.Optional.of(role(14L, "[\"*\"]")));

        PermissionService svc = newService();
        // 通配符持有者：有效集合只含 "*"，但任意权限码均判定通过
        assertEquals(Set.of("*"), svc.effectivePermissions(3L));
        assertTrue(svc.hasUserPermission(3L, "asset:create"));
        assertTrue(svc.hasUserPermission(3L, "anything:whatever"));
    }

    // ---------- 4) Redis 读失败 → 降级直查 DB ----------
    @Test
    void redisReadFailure_fallsBackToDb() {
        when(redisTemplate.opsForSet()).thenThrow(new RuntimeException("Redis Connection refused"));
        when(userRoleRepository.findByUserId(4L)).thenReturn(List.of(userRole(15L)));
        when(packageRepository.findByUserId(4L)).thenReturn(List.of());
        when(roleRepository.findById(15L)).thenReturn(java.util.Optional.of(
                role(15L, "[\"asset:create\",\"asset:export\"]")));

        Set<String> perms = newService().effectivePermissions(4L);

        // 即使 Redis 不可用，仍从 DB 正确聚合出权限位
        assertEquals(Set.of("asset:create", "asset:export"), perms);
    }

    // ---------- 5) 缓存命中 → 不回源 DB ----------
    @Test
    void cacheHit_returnsCachedWithoutHittingDb() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(eq("perm:5"))).thenReturn(new HashSet<>(List.of("asset:create")));

        Set<String> perms = newService().effectivePermissions(5L);

        assertEquals(Set.of("asset:create"), perms);
        // 命中缓存后不应再查角色仓储
        verify(roleRepository, never()).findById(anyLong());
        verify(userRoleRepository, never()).findByUserId(anyLong());
    }

    // ---------- 6) 变更后主动 evict ----------
    @Test
    void evictByRole_clearsAllAffectedUserCaches() {
        // 角色 99 被用户 7（直接角色）与用户 5（角色包）持有，二者缓存均须清除
        when(userRoleRepository.findByRoleId(99L)).thenReturn(
                List.of(UserRole.builder().userId(7L).roleId(99L).build()));
        when(packageRepository.findByRoleId(99L)).thenReturn(
                List.of(UserRolePackage.builder().userId(5L).roleId(99L).build()));

        newService().evictByRole(99L);

        verify(redisTemplate).delete("perm:5");
        verify(redisTemplate).delete("perm:7");
    }

    @Test
    void evictUser_deletesCacheKey() {
        newService().evictUser(8L);
        verify(redisTemplate).delete(eq("perm:8"));
    }

    // ---------- 7) 角色继承：子角色自动继承父角色 grants ----------
    @Test
    void inheritanceMerge_childInheritsParentGrants() {
        // 父角色 70 拥有 asset:read / asset:write；子角色 71 自身仅有 asset:delete，parent_id = 70
        Role parent = role(70L, "[\"asset:read\",\"asset:write\"]");
        Role child = role(71L, "[\"asset:delete\"]");
        child.setParentId(70L);

        when(userRoleRepository.findByUserId(6L)).thenReturn(List.of(userRole(71L)));
        when(packageRepository.findByUserId(6L)).thenReturn(List.of());
        when(roleRepository.findById(71L)).thenReturn(java.util.Optional.of(child));
        when(roleRepository.findById(70L)).thenReturn(java.util.Optional.of(parent));

        Set<String> perms = newService().effectivePermissions(6L);

        assertEquals(Set.of("asset:read", "asset:write", "asset:delete"), perms,
                "子角色应自动合并父角色的 grants");
    }

    // ---------- 8) 角色继承：无父角色时行为不变 ----------
    @Test
    void inheritanceMerge_noParentUnchanged() {
        Role solo = role(72L, "[\"asset:read\"]");

        when(userRoleRepository.findByUserId(9L)).thenReturn(List.of(userRole(72L)));
        when(packageRepository.findByUserId(9L)).thenReturn(List.of());
        when(roleRepository.findById(72L)).thenReturn(java.util.Optional.of(solo));

        assertEquals(Set.of("asset:read"), newService().effectivePermissions(9L),
                "无父角色时仅含自身 grants，行为不变");
    }
}
