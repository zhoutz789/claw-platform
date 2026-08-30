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
 * <p>账号（user）↔ 业务主体（厂家 MANUFACTURER / 服务站 STATION）绑定。
 * 绑定即授予对应角色包（MANUFACTURER/STATION，其 grants 已在 V47 预置），解绑即回收，全程清权限缓存。
 *
 * <p>设计铁律：同一账号对同一种主体类型仅允许 1 条绑定（UNIQUE(user_id, principal_type) 兜底）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrincipalBindingService {

    private final PrincipalBindingRepository bindingRepository;
    private final RoleGrantService roleGrantService;

    /** 绑定账号到业务主体（1:1 校验）。principalType ∈ {MANUFACTURER, STATION}。 */
    @Transactional
    public PrincipalBinding bind(Long userId, String principalType, Long principalId) {
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
        // 绑定即授予对应业务角色包（MANUFACTURER / STATION），其 grants 已在 V47 预置
        try {
            roleGrantService.grantByEvent(userId, principalType);
        } catch (Exception e) {
            log.warn("主体绑定授予角色包失败 userId={} type={}: {}", userId, principalType, e.getMessage());
        }
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
