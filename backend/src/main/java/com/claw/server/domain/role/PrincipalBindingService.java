package com.claw.server.domain.role;

import com.claw.server.common.api.BizException;
import com.claw.server.common.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 主体绑定服务（增量 A · Q5 严格 1:1）。
 *
 * <p>账号（user）↔ 业务主体绑定。支持的三类主体：
 * 厂家 {@code MANUFACTURER} / 服务站 {@code STATION} / <b>商家 {@code MERCHANT}</b>
 * （增量 C · Q9 拍板商家为独立主体，V60 扩展 principal_type CHECK，V62 新增 MERCHANT 角色模板）。
 *
 * <p>绑定即授予对应角色包（MANUFACTURER/STATION/MERCHANT，其 grants 由模板回写，见 V62），
 * 解绑即回收，全程清权限缓存与主体解析缓存。
 *
 * <p>设计铁律：同一账号对同一种主体类型仅允许 1 条绑定（UNIQUE(user_id, principal_type) 兜底）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrincipalBindingService {

    private final PrincipalBindingRepository bindingRepository;
    private final RoleGrantService roleGrantService;
    /** 主体解析门面（增量 C 新增）：绑定/解绑后需清其缓存，否则子账号回溯会读到旧主体。 */
    private final PrincipalResolver principalResolver;

    /**
     * 绑定账号到业务主体（1:1 校验）。
     *
     * @param principalType MANUFACTURER / STATION / MERCHANT
     */
    @Transactional
    public PrincipalBinding bind(Long userId, String principalType, Long principalId) {
        // 校验主体类型合法（非法码直接 404，避免脏数据落到 principal_type 的 CHECK 约束上）
        com.claw.server.common.enums.PrincipalType.of(principalType);
        if (bindingRepository.existsByUserIdAndPrincipalType(userId, principalType)) {
            throw BizException.of(40910, "principal.binding.exists");
        }
        PrincipalBinding binding = PrincipalBinding.builder()
                .userId(userId)
                .principalType(principalType)
                .principalId(principalId)
                .createdAt(Instant.now())
                .build();
        binding = bindingRepository.save(binding);
        // 绑定即授予对应业务角色包（MANUFACTURER / STATION / MERCHANT），其 grants 由 V62 按模板回写
        try {
            roleGrantService.grantByEvent(userId, principalType);
        } catch (Exception e) {
            log.warn("主体绑定授予角色包失败 userId={} type={}: {}", userId, principalType, e.getMessage());
        }
        principalResolver.evict(userId);
        log.info("主体绑定 userId={} type={} principalId={}", userId, principalType, principalId);
        return binding;
    }

    /** 解绑（回收角色包 + 删除绑定行）。 */
    @Transactional
    public void unbind(Long userId, String principalType) {
        bindingRepository.findByUserIdAndPrincipalType(userId, principalType).ifPresent(b -> {
            bindingRepository.delete(b);
            try {
                roleGrantService.revoke(userId, principalType);
            } catch (Exception e) {
                log.warn("主体解绑回收角色包失败 userId={} type={}: {}", userId, principalType, e.getMessage());
            }
            log.info("主体解绑 userId={} type={}", userId, principalType);
        });
        principalResolver.evict(userId);
    }

    @Transactional(readOnly = true)
    public List<PrincipalBinding> listByUser(Long userId) {
        return bindingRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<PrincipalBinding> listByPrincipal(String principalType, Long principalId) {
        return bindingRepository.findByPrincipalTypeAndPrincipalId(principalType, principalId);
    }

    /** 取当前登录账号对指定主体的绑定（供增量 B 数据范围解析）。 */
    @Transactional(readOnly = true)
    public PrincipalBinding currentBinding(String principalType) {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            return null;
        }
        return bindingRepository.findByUserIdAndPrincipalType(uid, principalType).orElse(null);
    }
}
