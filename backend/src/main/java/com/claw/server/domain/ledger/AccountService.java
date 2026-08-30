package com.claw.server.domain.ledger;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.AccountType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 账户服务：开户（用户总账户/平台内部户/三专户）、余额查询。
 * 平台内部户 user_id 为空；三专户对应 D27 escrow 口径（资金所有权归用户/投资者）。
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    /** 平台内部户（MASTER / RESIDUAL_RESERVE / BATTERY_FUND / VEHICLE_RISK），不存在则创建。 */
    @Transactional
    public Account getOrCreatePlatformAccount(AccountType type) {
        return accountRepository.findByUserIdIsNullAndAccountTypeAndCurrency(type, "USD")
                .orElseGet(() -> accountRepository.save(Account.builder().accountType(type).build()));
    }

    /** 用户总账户（MASTER），不存在则创建。 */
    @Transactional
    public Account getOrCreateUserAccount(Long userId) {
        return accountRepository.findByUserIdAndAccountTypeAndCurrency(userId, AccountType.MASTER, "USD")
                .orElseGet(() -> accountRepository.save(Account.builder()
                        .userId(userId).accountType(AccountType.MASTER).build()));
    }

    /** 用户功能子账户（如 DEPOSIT_LOCKED 押金冻结户），不存在则创建。 */
    @Transactional
    public Account getOrCreateSubAccount(Long userId, AccountType type) {
        return accountRepository.findByUserIdAndAccountTypeAndCurrency(userId, type, "USD")
                .orElseGet(() -> accountRepository.save(Account.builder()
                        .userId(userId).accountType(type).build()));
    }

    /** 查询用户子账户（只读，不自动创建）。供结算/履约域判定账户是否存在（缺失则转人工）。 */
    @Transactional(readOnly = true)
    public Optional<Account> findSubAccount(Long userId, AccountType type) {
        if (userId == null || type == null) {
            return Optional.empty();
        }
        return accountRepository.findByUserIdAndAccountType(userId, type).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<LedgerViews.AccountView> listAccounts(Long userId, AccountType accountType) {
        List<Account> list;
        if (userId != null && accountType != null) {
            list = accountRepository.findByUserIdAndAccountType(userId, accountType);
        } else if (userId != null) {
            list = accountRepository.findByUserId(userId);
        } else if (accountType != null) {
            list = accountRepository.findByAccountType(accountType);
        } else {
            list = accountRepository.findAll();
        }
        return list.stream().map(this::toView).toList();
    }

    /**
     * 跨域资金写入的唯一出口。
     * ArchUnit 铁律：资金域仓储（*Repository）只允许本域访问，其他域必须通过 AccountService/LedgerService 交互。
     * 因此履约/结算域调整余额、冻结额时一律走本方法，不得注入 AccountRepository。
     */
    @Transactional
    public Account saveAccount(Account account) {
        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public LedgerViews.AccountView getAccount(Long id) {
        return toView(accountRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("error.account.not.found")));
    }

    private LedgerViews.AccountView toView(Account a) {
        return new LedgerViews.AccountView(a.getId(), a.getUserId(), a.getAccountType(),
                a.getCurrency(), a.getBalance(), a.getFrozen());
    }
}
