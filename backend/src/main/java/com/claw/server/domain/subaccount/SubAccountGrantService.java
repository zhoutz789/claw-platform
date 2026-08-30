package com.claw.server.domain.subaccount;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.GrantMode;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.domain.role.PermissionRepository;
import com.claw.server.domain.role.PermissionService;
import com.claw.server.domain.role.PrincipalResolver;
import com.claw.server.domain.role.RoleTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 子账号授权服务（增量 C · O29 / O30）。
 *
 * <p><b>两种模式</b>：
 * <dl>
 *   <dt>{@link GrantMode#ALL}（全部功能）</dt>
 *   <dd>只写 {@code templateCode}，<b>不落明细</b>；运行时展开
 *       {@code role_template_permissions(template_code)}。
 *       平台新增功能时只需注册新权限码并挂到模板 —— <b>已授权 ALL 的子账号零改动自动获得</b>。
 *       这是周老板「以后功能也会不断的增加扩展」诉求的核心机制。</dd>
 *   <dt>{@link GrantMode#PARTIAL}（部分功能）</dt>
 *   <dd>落明细到 {@code sub_account_grant_items}；实际生效集合 =
 *       <b>明细 ∩ 主账号模板集合</b>（O30 交集防越权）。</dd>
 * </dl>
 *
 * <p>授权变更后立即清权限缓存（{@code PermissionService#evictUser}），保证 5s 内生效。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubAccountGrantService {

    private final SubAccountGrantRepository grantRepository;
    private final SubAccountGrantItemRepository itemRepository;
    private final SubAccountRepository subAccountRepository;
    private final PermissionRepository permissionRepository;
    private final RoleTemplateService roleTemplateService;
    private final PermissionService permissionService;
    private final PrincipalResolver principalResolver;

    /**
     * 授权（全量覆盖：一个子账号一份授权）。
     *
     * @param subAccountId    子账号 ID
     * @param grantMode       ALL / PARTIAL
     * @param permissionCodes PARTIAL 模式必填；ALL 模式忽略（只存模板码）
     * @param operatorId      授权人（主账号登录用户）
     * @return 授权结果（含被剔除的越权项）
     */
    @Transactional
    public GrantResult grant(Long subAccountId, String grantMode, List<String> permissionCodes, Long operatorId) {
        SubAccount sa = subAccountRepository.findById(subAccountId)
                .orElseThrow(() -> BizException.of(40401, "subaccount.not.found"));
        if (!sa.isActive()) {
            throw BizException.of(40940, "subaccount.not.active");
        }
        GrantMode mode = parseMode(grantMode);
        PrincipalType ownerType = PrincipalType.of(sa.getOwnerPrincipalType());
        String templateCode = ownerType.name();
        Set<String> ownerPerms = roleTemplateService.expandPermissions(templateCode);

        // 清旧授权（一个子账号一份）
        Optional<SubAccountGrant> existing = grantRepository.findTopBySubAccountIdOrderByIdDesc(subAccountId);
        existing.ifPresent(old -> itemRepository.deleteByGrantId(old.getId()));

        List<String> removed = new ArrayList<>();
        Set<String> effective = new TreeSet<>();
        if (mode == GrantMode.ALL) {
            // 不落明细：运行时展开模板，未来新功能自动继承
            effective.addAll(ownerPerms);
        } else {
            if (permissionCodes == null || permissionCodes.isEmpty()) {
                throw BizException.of(10001, "subaccount.grant.items.required");
            }
            SubAccountGrant g = upsertGrant(sa.getId(), mode, templateCode, operatorId);
            for (String code : new LinkedHashSet<>(permissionCodes)) {
                if (permissionRepository.findByCode(code).isEmpty()) {
                    throw BizException.of(40401, "permission.not.found", code);
                }
                // O30 交集防越权：主账号模板没有的码一律剔除
                if (!ownerPerms.contains(code)) {
                    removed.add(code);
                    continue;
                }
                itemRepository.save(SubAccountGrantItem.builder()
                        .grantId(g.getId())
                        .permissionCode(code)
                        .createdAt(Instant.now())
                        .build());
                effective.add(code);
            }
            // 授权变更后立即清缓存，保证「改权限后 5s 内生效」
            permissionService.evictUser(sa.getUserId());
            principalResolver.evict(sa.getUserId());
            log.info("子账号 {} 部分授权：勾选 {} 项，生效 {} 项，剔除越权 {} 项",
                    subAccountId, permissionCodes.size(), effective.size(), removed.size());
            return new GrantResult(g.getId(), mode.name(), effective, removed, templateCode);
        }

        SubAccountGrant g = upsertGrant(sa.getId(), mode, templateCode, operatorId);
        permissionService.evictUser(sa.getUserId());
        principalResolver.evict(sa.getUserId());
        log.info("子账号 {} 全部功能授权（跟随模板 {}，展开 {} 项）", subAccountId, templateCode, effective.size());
        return new GrantResult(g.getId(), mode.name(), effective, removed, templateCode);
    }

    /**
     * 计算子账号的生效权限集合（供 {@code PermissionService} 合并与前端预览）。
     *
     * <pre>
     * ALL     → 主账号模板全量
     * PARTIAL → grant_items ∩ 主账号模板
     * </pre>
     */
    @Transactional(readOnly = true)
    public Set<String> effectivePermissions(Long subAccountId) {
        SubAccount sa = subAccountRepository.findById(subAccountId)
                .orElseThrow(() -> BizException.of(40401, "subaccount.not.found"));
        Set<String> ownerPerms = roleTemplateService.expandPermissions(
                PrincipalType.of(sa.getOwnerPrincipalType()).name());
        Optional<SubAccountGrant> g = grantRepository.findBySubAccountIdAndStatus(subAccountId, "ACTIVE");
        if (g.isEmpty()) {
            return Set.of();
        }
        if (g.get().isAll()) {
            return ownerPerms;
        }
        List<String> items = itemRepository.findByGrantId(g.get().getId()).stream()
                .map(SubAccountGrantItem::getPermissionCode)
                .toList();
        Set<String> out = new TreeSet<>(items);
        out.retainAll(ownerPerms);   // 交集防越权
        return out;
    }

    /** 取子账号的授权（含明细），供管理页回显。 */
    @Transactional(readOnly = true)
    public Optional<SubAccountGrant> currentGrant(Long subAccountId) {
        return grantRepository.findTopBySubAccountIdOrderByIdDesc(subAccountId);
    }

    /** 取授权明细（PARTIAL 模式）。 */
    @Transactional(readOnly = true)
    public List<String> grantItems(Long grantId) {
        return itemRepository.findByGrantId(grantId).stream()
                .map(SubAccountGrantItem::getPermissionCode)
                .toList();
    }

    /** 撤销授权（子账号回到无任何授权状态）。 */
    @Transactional
    public void revoke(Long subAccountId, Long operatorId) {
        SubAccount sa = subAccountRepository.findById(subAccountId)
                .orElseThrow(() -> BizException.of(40401, "subaccount.not.found"));
        grantRepository.findBySubAccountIdAndStatus(subAccountId, "ACTIVE").ifPresent(g -> {
            g.setStatus("REVOKED");
            grantRepository.save(g);
            itemRepository.deleteByGrantId(g.getId());
        });
        permissionService.evictUser(sa.getUserId());
        log.info("子账号 {} 授权已撤销 operator={}", subAccountId, operatorId);
    }

    /**
     * 按登录用户查其子账号授权（子账号登录后计算自身权限用）。
     *
     * @param userId 子账号登录用户 ID
     * @return 生效权限集合；非子账号返回 empty
     */
    @Transactional(readOnly = true)
    public Optional<Set<String>> effectivePermissionsByUser(Long userId) {
        return subAccountRepository.findTopByUserIdAndStatus(userId, "ACTIVE")
                .filter(SubAccount::isActive)
                .map(sa -> effectivePermissions(sa.getId()));
    }

    private SubAccountGrant upsertGrant(Long subAccountId, GrantMode mode, String templateCode, Long operatorId) {
        Optional<SubAccountGrant> existing = grantRepository.findTopBySubAccountIdOrderByIdDesc(subAccountId);
        if (existing.isPresent()) {
            SubAccountGrant g = existing.get();
            g.setGrantMode(mode.name());
            g.setTemplateCode(mode == GrantMode.ALL ? templateCode : null);
            g.setGrantedBy(operatorId);
            g.setGrantedAt(Instant.now());
            g.setStatus("ACTIVE");
            return grantRepository.save(g);
        }
        return grantRepository.save(SubAccountGrant.builder()
                .subAccountId(subAccountId)
                .grantMode(mode.name())
                .templateCode(mode == GrantMode.ALL ? templateCode : null)
                .grantedBy(operatorId)
                .grantedAt(Instant.now())
                .status("ACTIVE")
                .build());
    }

    private static GrantMode parseMode(String grantMode) {
        if (grantMode == null || grantMode.isBlank()) {
            throw BizException.of(10001, "subaccount.grant.mode.required");
        }
        try {
            return GrantMode.valueOf(grantMode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.of(10001, "subaccount.grant.mode.invalid", grantMode);
        }
    }
}
