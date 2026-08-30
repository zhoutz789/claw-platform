package com.claw.server.domain.subaccount;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.domain.role.PermissionService;
import com.claw.server.domain.role.PrincipalResolver;
import com.claw.server.domain.user.User;
import com.claw.server.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 子账号服务（增量 C · O28 / O32）。
 *
 * <p>主账号（服务站管理者 / 厂家管理员 / 商家管理员）为协作成员开设子账号。
 *
 * <p><b>Q11：子账号不可再开子账号</b> —— 创建时校验当前操作人必须是主账号
 * （{@code PrincipalRef.viaSubAccount == false}），避免权限扩散与责任链失控。
 *
 * <p>停用子账号后其登录被拒、操作日志（{@code audit_logs} + 申请单日志）保留。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubAccountService {

    private final SubAccountRepository subAccountRepository;
    private final SubAccountGrantRepository grantRepository;
    private final UserRepository userRepository;
    private final PrincipalResolver principalResolver;
    private final PermissionService permissionService;

    /**
     * 新建子账号。
     *
     * @param ownerPrincipalType 主账号主体类型（通常由 {@link PrincipalResolver} 解析，也可由平台管理员指定）
     * @param ownerPrincipalId   主账号主体 ID
     * @param loginOrPhone       子账号登录用户的手机号（系统据此解析 users.id）
     * @param displayName        展示名
     * @param operatorId         操作人（主账号登录用户）
     * @return 新建的子账号
     * @throws BizException 40301 subaccount.owner.required（操作人无主体）
     * @throws BizException 40302 subaccount.nested.not.allowed（子账号不允许再开子账号，Q11）
     * @throws BizException 40901 subaccount.duplicate（同一主体重复添加）
     */
    @Transactional
    public SubAccount create(String ownerPrincipalType, Long ownerPrincipalId, String loginOrPhone,
                             String displayName, Long operatorId) {
        PrincipalResolver.PrincipalRef owner = resolveOwner(ownerPrincipalType, ownerPrincipalId, operatorId);
        // Q11：子账号不可再开子账号
        Optional<PrincipalResolver.PrincipalRef> current = principalResolver.resolve(operatorId);
        if (current.isPresent() && current.get().viaSubAccount()) {
            throw BizException.of(40302, "subaccount.nested.not.allowed");
        }
        User user = userRepository.findByPhone(loginOrPhone)
                .orElseThrow(() -> BizException.of(40401, "user.not.found"));
        if (subAccountRepository.existsByOwnerPrincipalTypeAndOwnerPrincipalIdAndUserId(
                owner.type().name(), owner.principalId(), user.getId())) {
            throw BizException.of(40901, "subaccount.duplicate");
        }
        SubAccount sa = SubAccount.builder()
                .ownerPrincipalType(owner.type().name())
                .ownerPrincipalId(owner.principalId())
                .userId(user.getId())
                .displayName(StringUtils.hasText(displayName) ? displayName : user.getFullName())
                .phone(loginOrPhone)
                .status("ACTIVE")
                .createdBy(operatorId)
                .build();
        SubAccount saved = subAccountRepository.save(sa);
        log.info("新建子账号 id={} owner={}#{} userId={} operator={}", saved.getId(),
                owner.type(), owner.principalId(), user.getId(), operatorId);
        return saved;
    }

    /** 按用户 ID 建子账号（平台管理员 / 前端已选好用户时用）。 */
    @Transactional
    public SubAccount createByUserId(String ownerPrincipalType, Long ownerPrincipalId, Long userId,
                                     String displayName, Long operatorId) {
        PrincipalResolver.PrincipalRef owner = resolveOwner(ownerPrincipalType, ownerPrincipalId, operatorId);
        userRepository.findById(userId)
                .orElseThrow(() -> BizException.of(40401, "user.not.found"));
        if (subAccountRepository.existsByOwnerPrincipalTypeAndOwnerPrincipalIdAndUserId(
                owner.type().name(), owner.principalId(), userId)) {
            throw BizException.of(40901, "subaccount.duplicate");
        }
        SubAccount sa = SubAccount.builder()
                .ownerPrincipalType(owner.type().name())
                .ownerPrincipalId(owner.principalId())
                .userId(userId)
                .displayName(displayName)
                .status("ACTIVE")
                .createdBy(operatorId)
                .build();
        return subAccountRepository.save(sa);
    }

    /** 列某主体的全部子账号。 */
    @Transactional(readOnly = true)
    public List<SubAccount> listByOwner(PrincipalType type, Long principalId) {
        return subAccountRepository.findByOwnerPrincipalTypeAndOwnerPrincipalIdOrderByCreatedAtDesc(
                type.name(), principalId);
    }

    /** 列当前登录账号所属主体的子账号（主账号视角）。 */
    @Transactional(readOnly = true)
    public List<SubAccount> listMine(Long operatorId) {
        PrincipalResolver.PrincipalRef ref = principalResolver.resolve(operatorId)
                .orElseThrow(() -> BizException.of(40301, "principal.not.resolved"));
        return listByOwner(ref.type(), ref.principalId());
    }

    /**
     * 停用子账号：状态置 DISABLED，其授权一并置 REVOKED，权限缓存立即失效。
     *
     * <p>操作日志保留（{@code audit_logs} 与业务留痕表均不删），保证可追溯（O32）。
     */
    @Transactional
    public SubAccount disable(Long subAccountId, Long operatorId) {
        SubAccount sa = load(subAccountId);
        if (!"ACTIVE".equals(sa.getStatus())) {
            throw BizException.of(40940, "subaccount.not.active");
        }
        sa.setStatus("DISABLED");
        sa.setDisabledAt(Instant.now());
        sa.setUpdatedAt(Instant.now());
        SubAccount saved = subAccountRepository.save(sa);
        grantRepository.findBySubAccountIdAndStatus(subAccountId, "ACTIVE").ifPresent(g -> {
            g.setStatus("REVOKED");
            grantRepository.save(g);
        });
        permissionService.evictUser(sa.getUserId());
        principalResolver.evict(sa.getUserId());
        log.info("子账号停用 id={} userId={} operator={}", subAccountId, sa.getUserId(), operatorId);
        return saved;
    }

    /** 启用子账号（恢复 ACTIVE，需重新授权才能拿到权限）。 */
    @Transactional
    public SubAccount enable(Long subAccountId, Long operatorId) {
        SubAccount sa = load(subAccountId);
        if ("ACTIVE".equals(sa.getStatus())) {
            throw BizException.of(40940, "subaccount.already.active");
        }
        sa.setStatus("ACTIVE");
        sa.setDisabledAt(null);
        sa.setUpdatedAt(Instant.now());
        SubAccount saved = subAccountRepository.save(sa);
        permissionService.evictUser(sa.getUserId());
        principalResolver.evict(sa.getUserId());
        log.info("子账号启用 id={} userId={} operator={}", subAccountId, sa.getUserId(), operatorId);
        return saved;
    }

    @Transactional(readOnly = true)
    public SubAccount load(Long subAccountId) {
        return subAccountRepository.findById(subAccountId)
                .orElseThrow(() -> BizException.of(40401, "subaccount.not.found"));
    }

    /** 子账号登录时校验：停用后拒绝登录（由认证链路调用）。 */
    @Transactional(readOnly = true)
    public boolean isUsable(Long userId) {
        return subAccountRepository.findTopByUserIdAndStatus(userId, "ACTIVE").isPresent();
    }

    /**
     * 解析 owner 主体：优先用显式入参，缺省时从当前操作人解析。
     *
     * @throws BizException 40301 subaccount.owner.required（操作人无业务主体）
     */
    private PrincipalResolver.PrincipalRef resolveOwner(String ownerPrincipalType, Long ownerPrincipalId,
                                                        Long operatorId) {
        if (StringUtils.hasText(ownerPrincipalType) && ownerPrincipalId != null) {
            return new PrincipalResolver.PrincipalRef(PrincipalType.of(ownerPrincipalType), ownerPrincipalId, false);
        }
        return principalResolver.resolve(operatorId)
                .orElseThrow(() -> BizException.of(40301, "subaccount.owner.required"));
    }
}
