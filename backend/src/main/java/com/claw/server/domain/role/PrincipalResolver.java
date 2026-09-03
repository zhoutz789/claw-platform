package com.claw.server.domain.role;

import com.claw.server.common.enums.PrincipalType;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.subaccount.SubAccount;
import com.claw.server.domain.subaccount.SubAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 主体解析门面（增量 C · §5.2）—— 解析「当前登录账号归属哪个业务主体」。
 *
 * <p>⚠️ 对 PRD O31 的事实更正：经核实 {@link DataScopeService#resolve} <b>根本不查
 * principal_bindings</b>，它只按角色 / 部门算 ALL/TYPE/DEPARTMENT/CUSTOM/SELF，
 * 产出的「数据范围」是<b>部门维度</b>，与业务主体无关。因此本轮<b>不动 @DataScope</b>，
 * 而是新增本门面：主体解析（我是谁的员工 / 哪个站）。
 *
 * <p><b>解析优先级</b>：
 * <ol>
 *   <li>{@code principal_bindings}（主账号）—— 已绑定账号直接命中；</li>
 *   <li>{@code sub_accounts}（子账号回溯）—— 经 owner_principal_* 回溯到主账号主体，
 *       这是子账号能看到本组织数据的前提（O31）；</li>
 *   <li>空（游客 / 平台管理员等无主体账号）。</li>
 * </ol>
 *
 * <p>缓存 {@code principal:{userId} -> "TYPE:id:0/1"}，TTL 300s；
 * Redis 不可达时静默降级直查 DB，绝不因缓存故障拒绝请求。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrincipalResolver {

    /** 主体引用：类型 + 主体 ID + 是否经子账号回溯而来。 */
    public record PrincipalRef(PrincipalType type, Long principalId, boolean viaSubAccount) {
    }

    private static final String CACHE_PREFIX = "principal:";
    private static final Duration CACHE_TTL = Duration.ofSeconds(300);

    private final PrincipalBindingRepository bindingRepository;
    private final SubAccountRepository subAccountRepository;
    /** Redis 可选：未配置 / 不可达时全程降级直查 DB。 */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /** 解析当前登录账号的主体。 */
    @Transactional(readOnly = true)
    public Optional<PrincipalRef> resolveCurrent() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            return Optional.empty();
        }
        return resolve(uid);
    }

    /** 解析指定账号的主体（带缓存）。 */
    @Transactional(readOnly = true)
    public Optional<PrincipalRef> resolve(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        Optional<PrincipalRef> cached = readCache(userId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<PrincipalRef> computed = compute(userId);
        computed.ifPresent(ref -> writeCache(userId, ref));
        return computed;
    }

    /** 解析当前账号主体，解析不到则抛 403（业务写入点用）。 */
    @Transactional(readOnly = true)
    public PrincipalRef resolveCurrentOrThrow() {
        return resolveCurrent()
                .orElseThrow(() -> new com.claw.server.common.api.BizException(40301, "principal.not.resolved"));
    }

    /** 是否经由子账号登录（Q11：子账号不可再开子账号）。 */
    @Transactional(readOnly = true)
    public boolean isSubAccount() {
        return resolveCurrent().map(PrincipalRef::viaSubAccount).orElse(false);
    }

    /** 清缓存（子账号授权 / 主体绑定变更后调用）。 */
    public void evict(Long userId) {
        if (redisTemplate == null || userId == null) {
            return;
        }
        try {
            redisTemplate.delete(CACHE_PREFIX + userId);
        } catch (Exception e) {
            log.warn("清除主体解析缓存失败，忽略：userId={}", userId, e);
        }
    }

    /**
     * 实际解析：先主账号绑定，再子账号回溯。
     *
     * <p>一个账号可能同时绑定多个主体（如既是厂家又是服务站），此处按
     * MANUFACTURER → STATION → MERCHANT 的固定优先级返回第一个，
     * 需要指定类型的调用方请用 {@link #resolveByType}。
     */
    @Transactional(readOnly = true)
    public Optional<PrincipalRef> compute(Long userId) {
        List<PrincipalBinding> bindings = bindingRepository.findByUserId(userId);
        for (PrincipalType type : List.of(PrincipalType.MANUFACTURER, PrincipalType.STATION, PrincipalType.MERCHANT)) {
            Optional<PrincipalRef> hit = bindings.stream()
                    .filter(b -> type.name().equals(b.getPrincipalType()))
                    .findFirst()
                    .map(b -> new PrincipalRef(type, b.getPrincipalId(), false));
            if (hit.isPresent()) {
                return hit;
            }
        }
        // ② 子账号回溯：经 sub_accounts 找到 owner 主体
        return subAccountRepository.findTopByUserIdAndStatus(userId, "ACTIVE")
                .map(this::toRef);
    }

    /** 解析当前账号对指定主体类型的主体 ID（无则 empty）。 */
    @Transactional(readOnly = true)
    public Optional<PrincipalRef> resolveByType(Long userId, PrincipalType type) {
        return resolve(userId).filter(ref -> ref.type() == type);
    }

    /**
     * 解析指定账号的【全部】主体绑定（多绑定账号返回多个，子账号回溯只算一个）。
     *
     * <p>与 {@link #resolve(Long)} 不同：{@code resolve} 按 MANUFACTURER → STATION → MERCHANT
     * 固定优先级只返回第一个；本方法返回该账号名下<b>所有</b>主体绑定（主账号多绑定 +
     * 子账号回溯到的 owner 主体），供作用域计算消费，避免「既是厂家又是服务站」的账号
     * 被错误收窄到单一类型（影响 {@code OrgWritableGuard.assertApplicantCanApply}）。
     *
     * <p>⚠️ 本方法<b>不复用</b> {@code principal:{userId}} 单值缓存（其结构为
     * {@code TYPE:id:0/1}，无法承载多值），直查 DB。库存页为低频读，可接受。
     * 既有的 {@code resolve/compute/resolveByType} 一律不改，避免影响既有链路。
     */
    @Transactional(readOnly = true)
    public List<PrincipalRef> resolveAll(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<PrincipalRef> refs = new ArrayList<>();
        for (PrincipalBinding b : bindingRepository.findByUserId(userId)) {
            try {
                PrincipalType t = PrincipalType.of(b.getPrincipalType());
                refs.add(new PrincipalRef(t, b.getPrincipalId(), false));
            } catch (RuntimeException e) {
                log.warn("主体绑定 principal_type 非法，跳过：userId={} type={}", userId, b.getPrincipalType());
            }
        }
        // 子账号回溯：补充 owner 主体（若尚未在主账号绑定里出现）
        subAccountRepository.findTopByUserIdAndStatus(userId, "ACTIVE").ifPresent(sa -> {
            PrincipalRef ref = toRef(sa);
            if (ref != null && refs.stream()
                    .noneMatch(r -> r.type() == ref.type() && r.principalId().equals(ref.principalId()))) {
                refs.add(ref);
            }
        });
        return refs;
    }

    private PrincipalRef toRef(SubAccount sa) {
        PrincipalType type;
        try {
            type = PrincipalType.of(sa.getOwnerPrincipalType());
        } catch (RuntimeException e) {
            log.warn("子账号 owner_principal_type 非法：id={} type={}", sa.getId(), sa.getOwnerPrincipalType());
            return null;
        }
        return new PrincipalRef(type, sa.getOwnerPrincipalId(), true);
    }

    // ---------------------- 缓存：principal:{userId} -> "TYPE:id:0/1" ----------------------

    private Optional<PrincipalRef> readCache(Long userId) {
        if (redisTemplate == null) {
            return Optional.empty();
        }
        try {
            String raw = redisTemplate.opsForValue().get(CACHE_PREFIX + userId);
            return parse(raw);
        } catch (Exception e) {
            log.warn("读取主体解析缓存失败，降级直查 DB：userId={}", userId, e);
            return Optional.empty();
        }
    }

    private void writeCache(Long userId, PrincipalRef ref) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(CACHE_PREFIX + userId,
                    ref.type().name() + ":" + ref.principalId() + ":" + (ref.viaSubAccount() ? 1 : 0),
                    CACHE_TTL);
        } catch (Exception e) {
            log.warn("回写主体解析缓存失败，忽略：userId={}", userId, e);
        }
    }

    private static Optional<PrincipalRef> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String[] parts = raw.split(":");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PrincipalRef(PrincipalType.of(parts[0]),
                    Long.parseLong(parts[1]), "1".equals(parts[2])));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
