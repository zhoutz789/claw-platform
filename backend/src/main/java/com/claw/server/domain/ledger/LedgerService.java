package com.claw.server.domain.ledger;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.BizType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 复式记账引擎（S2 核心）。
 *
 * <p>约束（技术文档 2.1 + V1 表结构）：
 * <ul>
 *   <li>每笔交易 N 条分录，借贷必须平衡（sum(D) == sum(C)），否则拒绝；</li>
 *   <li>同 bizType + bizRef 只允许入账一次（幂等键，DB 唯一索引兜底）；</li>
 *   <li>出账（D）前校验账户余额充足（balance - amount &gt;= 0，DB CHECK 兜底）；</li>
 *   <li>同一 txnId 的所有分录在同一事务内原子写入并同步账户余额。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {

    private final AccountRepository accountRepository;
    private final AccountEntryRepository entryRepository;

    /**
     * 复式记账：原子写入分录并更新账户余额。
     *
     * @param bizType 业务类型
     * @param bizRef  业务单号（幂等键；为空则不幂等，仅内部调用）
     * @param entries 分录列表（借贷必须平衡）
     * @return 入账结果（txnId 等）
     */
    @Transactional
    public LedgerViews.TxnResult postEntries(BizType bizType, String bizRef, List<LedgerRequests.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw BizException.invalidParam("error.ledger.entries.empty");
        }
        String bizTypeStr = bizType.name();
        if (bizRef != null && entryRepository.existsByBizTypeAndBizRef(bizTypeStr, bizRef)) {
            throw BizException.of(40950, "error.ledger.duplicate");
        }

        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (LedgerRequests.Entry e : entries) {
            if (e.direction() == LedgerRequests.Direction.D) {
                debit = debit.add(e.amount());
            } else {
                credit = credit.add(e.amount());
            }
        }
        if (debit.compareTo(credit) != 0) {
            throw BizException.of(42250, "error.ledger.unbalanced");
        }

        UUID txnId = UUID.randomUUID();
        List<Account> touched = new ArrayList<>();
        for (LedgerRequests.Entry e : entries) {
            Account account = accountRepository.findById(e.accountId())
                    .orElseThrow(() -> BizException.notFound("error.account.not.found"));
            if (e.direction() == LedgerRequests.Direction.D) {
                if (account.getBalance().compareTo(e.amount()) < 0) {
                    throw BizException.of(42251, "error.ledger.insufficient");
                }
                account.setBalance(account.getBalance().subtract(e.amount()));
            } else {
                account.setBalance(account.getBalance().add(e.amount()));
            }
            account.setUpdatedAt(Instant.now());
            accountRepository.save(account);

            entryRepository.save(AccountEntry.builder()
                    .txnId(txnId)
                    .accountId(e.accountId())
                    .direction(e.direction().name())
                    .amount(e.amount())
                    .bizType(bizTypeStr)
                    .bizRef(bizRef)
                    .memo(e.memo())
                    .build());
            touched.add(account);
        }

        log.info("复式记账 txn={} biz={}/{} entries={} amount={}", txnId, bizType, bizRef,
                entries.size(), debit);
        return new LedgerViews.TxnResult(txnId, bizType, bizRef, entries.size(),
                debit, touched.stream().map(this::toAccountView).toList());
    }

    /** 按 txnId 查询一笔交易的完整分录。 */
    @Transactional(readOnly = true)
    public List<LedgerViews.EntryView> entriesOfTxn(UUID txnId) {
        return entryRepository.findByTxnId(txnId).stream()
                .map(e -> new LedgerViews.EntryView(e.getId(), e.getTxnId(), e.getAccountId(),
                        e.getDirection(), e.getAmount(), e.getBizType(), e.getBizRef(), e.getMemo(), e.getCreatedAt()))
                .toList();
    }

    /** 查询账户最近流水。 */
    @Transactional(readOnly = true)
    public List<LedgerViews.EntryView> entriesOfAccount(Long accountId) {
        return entryRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(e -> new LedgerViews.EntryView(e.getId(), e.getTxnId(), e.getAccountId(),
                        e.getDirection(), e.getAmount(), e.getBizType(), e.getBizRef(), e.getMemo(), e.getCreatedAt()))
                .toList();
    }

    private LedgerViews.AccountView toAccountView(Account a) {
        return new LedgerViews.AccountView(a.getId(), a.getUserId(), a.getAccountType(),
                a.getCurrency(), a.getBalance(), a.getFrozen());
    }
}
