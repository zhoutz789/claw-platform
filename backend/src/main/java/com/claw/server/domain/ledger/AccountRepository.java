package com.claw.server.domain.ledger;

import com.claw.server.common.enums.AccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * 悲观写锁查询（T-S1 并发安全修复）。
     * 在 LedgerService.postEntries 事务内使用，确保并发换电/结算时余额不被竞态覆盖。
     * SELECT ... FOR UPDATE 在 PostgreSQL 下生效。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    /**
     * 悲观写锁按用户+类型查询（用于押金冻结/退还场景的并发安全）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.userId = :userId AND a.accountType = :type AND a.currency = :currency")
    Optional<Account> findByUserIdAndTypeForUpdate(@Param("userId") Long userId,
                                                    @Param("type") AccountType accountType,
                                                    @Param("currency") String currency);

    Optional<Account> findByUserIdAndAccountTypeAndCurrency(Long userId, AccountType accountType, String currency);

    Optional<Account> findByUserIdIsNullAndAccountTypeAndCurrency(AccountType accountType, String currency);

    List<Account> findByUserId(Long userId);

    List<Account> findByUserIdAndAccountType(Long userId, AccountType accountType);

    List<Account> findByAccountType(AccountType accountType);
}
