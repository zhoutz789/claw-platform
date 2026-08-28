package com.claw.server.integration;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountEntryRepository;
import com.claw.server.domain.ledger.AccountRepository;
import com.claw.server.domain.ledger.LedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 复式记账引擎集成测试（B2）：在真实 PostgreSQL 上验证
 * <ul>
 *   <li>借贷平衡约束（不平衡 → 42250）</li>
 *   <li>同 bizType+bizRef 幂等（重复 → 40950）</li>
 *   <li>出账余额充足校验（不足 → 42251）</li>
 *   <li>悲观写锁（T-S1）路径在 PG 上可执行，余额与分录原子落地</li>
 *   <li>金额精度 NUMERIC(18,4) 不丢精度</li>
 * </ul>
 */
class LedgerDoubleEntryIT extends AbstractIntegrationTest {

    @Autowired
    LedgerService ledgerService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    AccountEntryRepository entryRepository;

    private Account newAccount(AccountType type, BigDecimal balance) {
        return accountRepository.save(Account.builder()
                .accountType(type)
                .currency("USD")
                .balance(balance)
                .build());
    }

    @Test
    void balancedEntriesUpdateBalancesAndPersist() {
        Account master = newAccount(AccountType.MASTER, new BigDecimal("1000.0000"));
        Account asset = newAccount(AccountType.ASSET, BigDecimal.ZERO);

        LedgerViews.TxnResult res = ledgerService.postEntries(
                BizType.SWAP_PAY, UUID.randomUUID().toString(),
                List.of(
                        new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.D, new BigDecimal("100.2500"), "pay"),
                        new LedgerRequests.Entry(asset.getId(), LedgerRequests.Direction.C, new BigDecimal("100.2500"), "recv")));

        assertEquals(2, res.entryCount());
        // 余额原子更新（NUMERIC(18,4) 精度保留）
        assertEquals(new BigDecimal("899.7500"), accountRepository.findById(master.getId()).orElseThrow().getBalance());
        assertEquals(new BigDecimal("100.2500"), accountRepository.findById(asset.getId()).orElseThrow().getBalance());
        // 双记账：master 1 条借方分录、asset 1 条贷方分录，合计 2 条落库
        assertEquals(1, entryRepository.findByAccountIdOrderByCreatedAtDesc(master.getId()).size());
        assertEquals(1, entryRepository.findByAccountIdOrderByCreatedAtDesc(asset.getId()).size());
        assertEquals(2, ledgerService.entriesOfTxn(res.txnId()).size());
    }

    @Test
    void unbalancedEntriesRejected() {
        Account master = newAccount(AccountType.MASTER, new BigDecimal("1000.0000"));
        Account asset = newAccount(AccountType.ASSET, BigDecimal.ZERO);

        BizException ex = assertThrows(BizException.class, () -> ledgerService.postEntries(
                BizType.SWAP_PAY, UUID.randomUUID().toString(),
                List.of(
                        new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.D, new BigDecimal("100.0000"), "pay"),
                        new LedgerRequests.Entry(asset.getId(), LedgerRequests.Direction.C, new BigDecimal("99.0000"), "recv"))));
        assertEquals(42250, ex.getCode());
    }

    @Test
    void duplicateBizRefRejected() {
        Account master = newAccount(AccountType.MASTER, new BigDecimal("1000.0000"));
        Account asset = newAccount(AccountType.ASSET, BigDecimal.ZERO);
        List<LedgerRequests.Entry> entries = List.of(
                new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.D, new BigDecimal("10.0000"), "p"),
                new LedgerRequests.Entry(asset.getId(), LedgerRequests.Direction.C, new BigDecimal("10.0000"), "r"));

        String dupRef = UUID.randomUUID().toString();
        ledgerService.postEntries(BizType.SWAP_PAY, dupRef, entries);
        BizException ex = assertThrows(BizException.class, () ->
                ledgerService.postEntries(BizType.SWAP_PAY, dupRef, entries));
        assertEquals(40950, ex.getCode());
    }

    @Test
    void insufficientBalanceRejected() {
        // 改用普通业务账户（ASSET）验证"余额不足拦截"：平台 MASTER 清算户已在 V37 豁免
        // （允许零/负，作 PROJECT_LEDGER 内部对冲侧），故不再用 MASTER 测拦截。
        Account asset = newAccount(AccountType.ASSET, BigDecimal.ZERO);
        Account other = newAccount(AccountType.ASSET, BigDecimal.ZERO);

        BizException ex = assertThrows(BizException.class, () -> ledgerService.postEntries(
                BizType.SWAP_PAY, UUID.randomUUID().toString(),
                List.of(
                        new LedgerRequests.Entry(asset.getId(), LedgerRequests.Direction.D, new BigDecimal("100.0000"), "pay"),
                        new LedgerRequests.Entry(other.getId(), LedgerRequests.Direction.C, new BigDecimal("100.0000"), "recv"))));
        assertEquals(42251, ex.getCode());
    }

    @Test
    void txnIdIsUuidAndTraceable() {
        Account master = newAccount(AccountType.MASTER, new BigDecimal("1000.0000"));
        Account asset = newAccount(AccountType.ASSET, BigDecimal.ZERO);
        LedgerViews.TxnResult res = ledgerService.postEntries(
                BizType.SWAP_PAY, UUID.randomUUID().toString(),
                List.of(
                        new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.D, new BigDecimal("50.0000"), "p"),
                        new LedgerRequests.Entry(asset.getId(), LedgerRequests.Direction.C, new BigDecimal("50.0000"), "r")));
        // 校验 txnId 可被解析为 UUID，且能按 txnId 回溯完整分录
        UUID txnId = res.txnId();
        assertEquals(2, ledgerService.entriesOfTxn(txnId).size());
    }
}
