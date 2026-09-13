package com.claw.server.domain.funds;

import com.claw.server.common.enums.CustodyOwnerType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 虚拟子户仓储（claw.virtual_subaccount）。
 */
public interface VirtualSubAccountRepository extends JpaRepository<VirtualSubAccount, Long> {

    /**
     * 幂等开户查询键：{@code (ownerType, ownerId, currency, fundsLocationId)}（对应部分唯一索引 uq_vsa_owner）。
     */
    Optional<VirtualSubAccount> findByOwnerTypeAndOwnerIdAndCurrencyAndFundsLocationIdAndDeletedFalse(
            CustodyOwnerType ownerType, Long ownerId, String currency, Long fundsLocationId);

    Optional<VirtualSubAccount> findByVsaNoAndDeletedFalse(String vsaNo);

    List<VirtualSubAccount> findByOwnerTypeAndOwnerIdAndCurrencyAndDeletedFalse(
            CustodyOwnerType ownerType, Long ownerId, String currency);

    List<VirtualSubAccount> findByFundsLocationIdAndDeletedFalse(Long fundsLocationId);

    boolean existsByVsaNo(String vsaNo);

    /**
     * 按持有方类型 + 币种 + 状态列出子户（用于 R1/R5 分账的收款方 ABA 账户解析，见 {@code PayeeAbaRefResolver}）。
     *
     * <p>口径说明：分账准入只要求「该收款方类型下存在一个已绑定 ABA 账户的 ACTIVE 子户」。
     * 精确到具体主体（{@code owner_id}）的映射依赖 {@code principal_bindings}，属落地设计 §11.4
     * 的待明确项 —— 在口径落定前此处按类型做<b>尽力解析</b>，解析不到即由调用方降级走周期批量。
     *
     * @param ownerType 持有方类型
     * @param currency  币种
     * @param status    子户状态（如 {@code ACTIVE}）
     * @return 按 id 升序的子户列表
     */
    List<VirtualSubAccount> findByOwnerTypeAndCurrencyAndStatusAndDeletedFalseOrderByIdAsc(
            CustodyOwnerType ownerType, String currency, String status);
}
