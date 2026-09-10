package com.claw.server.domain.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountEntryRepository extends JpaRepository<AccountEntry, Long> {

    boolean existsByBizTypeAndBizRef(String bizType, String bizRef);

    /** 按业务单号（幂等键）反查所有分录，供任务收益对账（C 方向即 provider 入账）。 */
    List<AccountEntry> findByBizRef(String bizRef);

    List<AccountEntry> findByTxnId(UUID txnId);

    List<AccountEntry> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    Optional<AccountEntry> findFirstByBizTypeAndBizRefOrderByCreatedAtAsc(String bizType, String bizRef);
}
