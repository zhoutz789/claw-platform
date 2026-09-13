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
}
