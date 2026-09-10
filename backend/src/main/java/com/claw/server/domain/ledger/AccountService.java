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
    private final AccountEntryRepository accountEntryRepository;

    /**
     * 平台内部户（MASTER / RESIDUAL_RESERVE / BATTERY_FUND / VEHICLE_RISK），不存在则创建。
     *
     * <p>★必须用 {@code findFirstBy...OrderByIdAsc} 而非 {@code findBy...}：平台内部户的
     * {@code user_id} 为 NULL，而 {@code accounts} 的唯一约束在 PostgreSQL 下对 NULL 互不相等
     * （NULL != NULL），故<b>无法阻止重复平台户</b>。历史并发/重复调用会在库中累积多条
     * user_id=NULL 的同类账户，此时 {@code findBy}（返回 Optional 单条）会抛
     * {@code NonUniqueResultException} 直接打断资金链路。取 id 最小的那条作为权威户即可容忍。
     */
    @Transactional
    public Account getOrCreatePlatformAccount(AccountType type) {
        return accountRepository.findFirstByUserIdIsNullAndAccountTypeAndCurrencyOrderByIdAsc(type, "USD")
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

    /**
     * 按业务单号（幂等键）反查所有账本分录（跨域只读出口）。
     *
     * <p>任务大厅收益对账等外部域经此读取，避免直持 {@code AccountEntryRepository}
     * （ArchUnit 守护 ledger 域封闭性：资金域仓储只允许本域访问）。返回的是 common 层
     * {@link LedgerViews.EntryView}，不向外泄漏 ledger 实体。
     *
     * @param bizRef 业务单号（如 TASK-&lt;taskId&gt;-&lt;assignmentId&gt;）
     * @return 该 bizRef 下的全部分录视图（含借贷方向、金额、memo、时间）
     */
    @Transactional(readOnly = true)
    public List<LedgerViews.EntryView> findEntriesByBizRef(String bizRef) {
        return accountEntryRepository.findByBizRef(bizRef).stream()
                .map(e -> new LedgerViews.EntryView(e.getId(), e.getTxnId(), e.getAccountId(),
                        e.getDirection(), e.getAmount(), e.getBizType(), e.getBizRef(), e.getMemo(),
                        e.getCreatedAt()))
                .toList();
    }

    private LedgerViews.AccountView toView(Account a) {
        return new LedgerViews.AccountView(a.getId(), a.getUserId(), a.getAccountType(),
                a.getCurrency(), a.getBalance(), a.getFrozen());
    }
}
