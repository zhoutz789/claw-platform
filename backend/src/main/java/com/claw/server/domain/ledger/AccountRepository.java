package com.claw.server.domain.ledger;

import com.claw.server.common.enums.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByUserIdAndAccountTypeAndCurrency(Long userId, AccountType accountType, String currency);

    Optional<Account> findByUserIdIsNullAndAccountTypeAndCurrency(AccountType accountType, String currency);

    List<Account> findByUserId(Long userId);

    List<Account> findByUserIdAndAccountType(Long userId, AccountType accountType);

    List<Account> findByAccountType(AccountType accountType);
}
