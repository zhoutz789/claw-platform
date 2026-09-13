package com.claw.server.domain.funds;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.CustodyOwnerType;
import com.claw.server.domain.ledger.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 虚拟子户服务（L2）：幂等开户 + 冻结/关闭 + 账本账户映射。
 *
 * <p><b>幂等开户</b>：键为 {@code (ownerType, ownerId, currency, fundsLocationId)}
 * （对应 V128 部分唯一索引 uq_vsa_owner）。命中既有子户直接返回，不重复开户。
 *
 * <p><b>账本账户映射</b>：{@link VirtualSubAccount#getOwnerUserId()} 即映射到的账本用户
 * （平台内部方为 NULL）。当其为非空时，开户同时经 {@link AccountService} 确保该用户存在
 * 账本账户（幂等），从而把「虚拟子户 ↔ 账本账户」映射落在用户维度。
 *
 * <p>本服务不直接写账本（不注入 LedgerService）：开户只建映射，不动余额；
 * 资金动作统一由 L5 清分层经 {@code LedgerService.postEntries} 发起。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VirtualSubAccountService {

    /** 子户状态：正常。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 子户状态：冻结。 */
    public static final String STATUS_FROZEN = "FROZEN";
    /** 子户状态：关闭。 */
    public static final String STATUS_CLOSED = "CLOSED";

    /** 默认币种（与 DDL DEFAULT 'USD' 一致）。 */
    public static final String DEFAULT_CURRENCY = "USD";

    /** 虚拟子户号生成的最大重试次数（防随机后缀碰撞）。 */
    private static final int VSA_NO_MAX_ATTEMPTS = 5;

    private final VirtualSubAccountRepository virtualSubAccountRepository;
    private final FundsLocationRepository fundsLocationRepository;
    private final AccountService accountService;

    /**
     * 幂等开户。
     *
     * @param ownerType       持有方类型（必填）
     * @param ownerId         持有方业务 id（必填）
     * @param ownerUserId     映射到的账本用户 id（平台内部方传 {@code null}）
     * @param fundsLocationId 所属托管点位 id（必填）
     * @param currency        币种（空则默认 USD）
     * @return 既有或新建的虚拟子户
     * @throws BizException 必填缺失或托管点位不存在
     */
    @Transactional
    public VirtualSubAccount open(CustodyOwnerType ownerType, Long ownerId, Long ownerUserId,
                                  Long fundsLocationId, String currency) {
        if (ownerType == null) {
            throw BizException.invalidParam("error.funds.subaccount.owner.type.missing");
        }
        if (ownerId == null) {
            throw BizException.invalidParam("error.funds.subaccount.owner.id.missing");
        }
        if (fundsLocationId == null) {
            throw BizException.invalidParam("error.funds.subaccount.location.missing");
        }
        String ccy = (currency == null || currency.trim().isEmpty()) ? DEFAULT_CURRENCY : currency.trim();

        // 幂等：命中既有子户直接返回（含 FROZEN/CLOSED，开不改变其状态）
        Optional<VirtualSubAccount> existing =
                virtualSubAccountRepository.findByOwnerTypeAndOwnerIdAndCurrencyAndFundsLocationIdAndDeletedFalse(
                        ownerType, ownerId, ccy, fundsLocationId);
        if (existing.isPresent()) {
            log.info("[Funds] 虚拟子户已存在（owner={}:{} ccy={} loc={}），跳过重复开户",
                    ownerType, ownerId, ccy, fundsLocationId);
            return existing.get();
        }

        // 所属托管点位必须存在（未删除）
        fundsLocationRepository.findById(fundsLocationId)
                .filter(l -> !Boolean.TRUE.equals(l.getDeleted()))
                .orElseThrow(() -> BizException.notFound("error.funds.location.not.found", fundsLocationId));

        // 账本账户映射：ownerUserId 非空时确保其账本账户存在（幂等，无余额动作）
        if (ownerUserId != null) {
            accountService.getOrCreateUserAccount(ownerUserId);
        }

        Instant now = Instant.now();
        VirtualSubAccount vsa = VirtualSubAccount.builder()
                .vsaNo(generateVsaNo(ownerType, ownerId, ccy))
                .ownerType(ownerType)
                .ownerId(ownerId)
                .ownerUserId(ownerUserId)
                .fundsLocationId(fundsLocationId)
                .currency(ccy)
                .status(STATUS_ACTIVE)
                .tenantId(1L)
                .deleted(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return virtualSubAccountRepository.save(vsa);
    }

    /**
     * 冻结子户（幂等：已冻结直接返回；已关闭拒绝）。
     *
     * @param id     子户 id
     * @param reason 冻结原因（留痕日志，表结构无该列）
     * @return 更新后的子户
     * @throws BizException 子户不存在或已关闭
     */
    @Transactional
    public VirtualSubAccount freeze(Long id, String reason) {
        VirtualSubAccount vsa = get(id);
        if (STATUS_CLOSED.equals(vsa.getStatus())) {
            throw BizException.of(40902, "error.funds.subaccount.closed", id);
        }
        if (STATUS_FROZEN.equals(vsa.getStatus())) {
            return vsa;
        }
        vsa.setStatus(STATUS_FROZEN);
        vsa.setUpdatedAt(Instant.now());
        log.info("[Funds] 冻结虚拟子户 {}（{}），原因：{}", vsa.getId(), vsa.getVsaNo(), reason);
        return virtualSubAccountRepository.save(vsa);
    }

    /**
     * 关闭子户（幂等：已关闭直接返回）。
     *
     * @param id 子户 id
     * @return 更新后的子户
     * @throws BizException 子户不存在
     */
    @Transactional
    public VirtualSubAccount close(Long id) {
        VirtualSubAccount vsa = get(id);
        if (STATUS_CLOSED.equals(vsa.getStatus())) {
            return vsa;
        }
        vsa.setStatus(STATUS_CLOSED);
        vsa.setUpdatedAt(Instant.now());
        log.info("[Funds] 关闭虚拟子户 {}（{}）", vsa.getId(), vsa.getVsaNo());
        return virtualSubAccountRepository.save(vsa);
    }

    /**
     * 查询子户（只读；同一 owner+币种可能命中多个点位，按 id 升序取第一条）。
     *
     * @param ownerType 持有方类型
     * @param ownerId   持有方业务 id
     * @param currency  币种（空则默认 USD）
     * @return 命中子户；参数缺失或无记录时返回 {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    public Optional<VirtualSubAccount> find(CustodyOwnerType ownerType, Long ownerId, String currency) {
        if (ownerType == null || ownerId == null) {
            return Optional.empty();
        }
        String ccy = (currency == null || currency.trim().isEmpty()) ? DEFAULT_CURRENCY : currency.trim();
        return virtualSubAccountRepository
                .findByOwnerTypeAndOwnerIdAndCurrencyAndDeletedFalse(ownerType, ownerId, ccy)
                .stream()
                .findFirst();
    }

    /**
     * 按主键取子户（不存在或已逻辑删除则抛异常）。
     *
     * @param id 子户 id
     * @return 命中子户
     * @throws BizException 子户不存在
     */
    @Transactional(readOnly = true)
    public VirtualSubAccount get(Long id) {
        return virtualSubAccountRepository.findById(id)
                .filter(v -> !Boolean.TRUE.equals(v.getDeleted()))
                .orElseThrow(() -> BizException.notFound("error.funds.subaccount.not.found", id));
    }

    /**
     * 生成虚拟子户号：{@code VSA-<ownerType>-<ownerId>-<currency>-<8HEX>}，碰撞则重试（有界）。
     *
     * @param ownerType 持有方类型
     * @param ownerId   持有方业务 id
     * @param currency  币种
     * @return 唯一子户号
     */
    private String generateVsaNo(CustodyOwnerType ownerType, Long ownerId, String currency) {
        for (int attempt = 0; attempt < VSA_NO_MAX_ATTEMPTS; attempt++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            String candidate = "VSA-" + ownerType.name() + "-" + ownerId + "-" + currency + "-" + suffix;
            if (!virtualSubAccountRepository.existsByVsaNo(candidate)) {
                return candidate;
            }
        }
        // 极低概率：退化为完整 UUID（仍保持格式前缀）
        return "VSA-" + ownerType.name() + "-" + ownerId + "-" + currency + "-"
                + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}
