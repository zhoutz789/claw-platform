package com.claw.server.domain.role;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.RoleSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 人人经济角色包授予引擎（技术文档 4.5）。
 *
 * <p>设计：角色 = 动态权限包，不是固定身份。授予来源三类：
 * <ul>
 *   <li>{@code AUTO}：满足 {@code roles.auto_grant} 条件自动授予（如注册即消费者）；</li>
 *   <li>{@code APPLY}：用户主动申请开通；</li>
 *   <li>{@code ADMIN}：平台/管理员人工授予（如服务站加盟审核通过）。</li>
 * </ul>
 * 后续「购车成功→车主」「开通物流功能→司机」等由对应域事件调用 {@link #grantByEvent} 触发。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleGrantService {

    private final RoleRepository roleRepository;
    private final UserRolePackageRepository packageRepository;

    /**
     * 注册钩子：为新建用户授予所有 {@code auto_grant=true} 的角色包（默认含 CONSUMER/PRODUCER/DISTRIBUTOR）。
     */
    @Transactional
    public void grantAutoRoles(Long userId) {
        List<Role> autoRoles = roleRepository.findAll().stream()
                .filter(r -> Boolean.TRUE.equals(r.getAutoGrant()))
                .toList();
        for (Role role : autoRoles) {
            ensurePackage(userId, role, RoleSource.AUTO);
        }
        log.info("用户 {} 自动授予角色包 {} 个", userId, autoRoles.size());
    }

    /** 用户主动申请开通某角色包。 */
    @Transactional
    public RoleView apply(Long userId, String roleCode) {
        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> BizException.of(40401, "role.not.found"));
        // 已生效则幂等返回
        UserRolePackage pkg = packageRepository.findByUserIdAndRoleId(userId, role.getId())
                .filter(UserRolePackage::isActive)
                .orElseGet(() -> savePackage(userId, role, RoleSource.APPLY));
        return RoleView.of(role.getId(), role.getCode(), role.getNameI18n(), pkg.getSource(), pkg.getGrantedAt(),
                role.getDataScope());
    }

    /** 域事件触发自动授予（如购车→车主、开通功能→司机）。 */
    @Transactional
    public void grantByEvent(Long userId, String roleCode) {
        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> BizException.of(40401, "role.not.found"));
        ensurePackage(userId, role, RoleSource.AUTO);
    }

    /** 回收角色包（功能停用/资产处置后权限即时失效）。 */
    @Transactional
    public void revoke(Long userId, String roleCode) {
        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> BizException.of(40401, "role.not.found"));
        packageRepository.findByUserIdAndRoleId(userId, role.getId()).ifPresent(pkg -> {
            pkg.setRevokedAt(Instant.now());
            packageRepository.save(pkg);
        });
    }

    /** 当前用户已生效的角色包（含角色码与名称）。 */
    @Transactional(readOnly = true)
    public List<RoleView> listActive(Long userId) {
        return packageRepository.findByUserId(userId).stream()
                .filter(UserRolePackage::isActive)
                .map(pkg -> {
                    Role role = roleRepository.findById(pkg.getRoleId()).orElseThrow();
                    return RoleView.of(role.getId(), role.getCode(), role.getNameI18n(), pkg.getSource(), pkg.getGrantedAt(),
                            role.getDataScope());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean hasPackage(Long userId, String roleCode) {
        return roleRepository.findByCode(roleCode)
                .map(role -> packageRepository.findByUserIdAndRoleId(userId, role.getId())
                        .map(UserRolePackage::isActive).orElse(false))
                .orElse(false);
    }

    private UserRolePackage ensurePackage(Long userId, Role role, RoleSource source) {
        return packageRepository.findByUserIdAndRoleId(userId, role.getId())
                .map(pkg -> {
                    if (!pkg.isActive()) {
                        pkg.setRevokedAt(null);
                        pkg.setSource(source);
                        pkg.setGrantedAt(Instant.now());
                        return packageRepository.save(pkg);
                    }
                    return pkg;
                })
                .orElseGet(() -> savePackage(userId, role, source));
    }

    private UserRolePackage savePackage(Long userId, Role role, RoleSource source) {
        UserRolePackage pkg = UserRolePackage.builder()
                .userId(userId)
                .roleId(role.getId())
                .source(source)
                .build();
        return packageRepository.save(pkg);
    }
}
