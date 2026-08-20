package com.claw.server.domain.deposit;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.DepositRequests;
import com.claw.server.common.dto.DepositViews;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 押金流转服务：支付冻结 → 归还 / 违约扣收。
 *
 * <p>记账口径（复式）：
 * <ul>
 *   <li>hold：用户 MASTER 出账 → 用户 DEPOSIT_LOCKED 入账（bizType=DEPOSIT_HOLD）；</li>
 *   <li>release：DEPOSIT_LOCKED 出账 → 用户 MASTER 入账；</li>
 *   <li>forfeit：DEPOSIT_LOCKED 出账 → 平台 RESIDUAL_RESERVE 入账（D27 残值准备金专户）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DepositService {

    private final DepositRepository depositRepository;
    private final AccountService accountService;
    private final LedgerService ledgerService;

    @Transactional
    public DepositViews.DepositView hold(DepositRequests.Hold req) {
        String depositNo = "DEP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Deposit deposit = depositRepository.save(Deposit.builder()
                .depositNo(depositNo)
                .userId(req.userId())
                .assetId(req.assetId())
                .amount(req.amount())
                .status("HELD")
                .payOrderNo(req.payOrderNo())
                .build());

        Account master = accountService.getOrCreateUserAccount(req.userId());
        Account locked = accountService.getOrCreateSubAccount(req.userId(), AccountType.DEPOSIT_LOCKED);
        ledgerService.postEntries(BizType.DEPOSIT_HOLD, depositNo, List.of(
                new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.D, req.amount(), "押金冻结"),
                new LedgerRequests.Entry(locked.getId(), LedgerRequests.Direction.C, req.amount(), "押金冻结 HELD")));
        log.info("押金 {} 冻结 ${} userId={}", depositNo, req.amount(), req.userId());
        return toView(deposit);
    }

    @Transactional
    public DepositViews.DepositView release(DepositRequests.Release req) {
        Deposit d = load(req.depositNo());
        requireStatus(d, "HELD");
        d.setStatus("RETURNED");
        d.setUpdatedAt(Instant.now());
        depositRepository.save(d);

        Account master = accountService.getOrCreateUserAccount(d.getUserId());
        Account locked = accountService.getOrCreateSubAccount(d.getUserId(), AccountType.DEPOSIT_LOCKED);
        /* 幂等键与 hold 区分：押金归还 = 同 bizType + 单号后缀 RELEASE */
        ledgerService.postEntries(BizType.DEPOSIT_HOLD, req.depositNo() + ":RELEASE", List.of(
                new LedgerRequests.Entry(locked.getId(), LedgerRequests.Direction.D, d.getAmount(), "押金归还"),
                new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.C, d.getAmount(), "押金归还")));
        return toView(d);
    }

    @Transactional
    public DepositViews.DepositView forfeit(String depositNo, String reason) {
        Deposit d = load(depositNo);
        requireStatus(d, "HELD");
        d.setStatus("FORFEITED");
        d.setForfeitReason(reason);
        d.setUpdatedAt(Instant.now());
        depositRepository.save(d);

        Account locked = accountService.getOrCreateSubAccount(d.getUserId(), AccountType.DEPOSIT_LOCKED);
        Account reserve = accountService.getOrCreatePlatformAccount(AccountType.RESIDUAL_RESERVE);
        /* 幂等键与 hold 区分：违约扣收 = 同 bizType + 单号后缀 FORFEIT */
        ledgerService.postEntries(BizType.DEPOSIT_HOLD, depositNo + ":FORFEIT", List.of(
                new LedgerRequests.Entry(locked.getId(), LedgerRequests.Direction.D, d.getAmount(), "违约扣收"),
                new LedgerRequests.Entry(reserve.getId(), LedgerRequests.Direction.C, d.getAmount(), "残值准备金专户")));
        log.info("押金 {} 扣收 ${} 转残值准备金，原因={}", depositNo, d.getAmount(), reason);
        return toView(d);
    }

    @Transactional(readOnly = true)
    public List<DepositViews.DepositView> listByUser(Long userId) {
        return depositRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toView).toList();
    }

    private Deposit load(String depositNo) {
        return depositRepository.findByDepositNo(depositNo)
                .orElseThrow(() -> BizException.notFound("error.deposit.not.found"));
    }

    private void requireStatus(Deposit d, String expected) {
        if (!expected.equals(d.getStatus())) {
            throw BizException.of(40951, "error.deposit.status");
        }
    }

    private DepositViews.DepositView toView(Deposit d) {
        return new DepositViews.DepositView(d.getId(), d.getDepositNo(), d.getUserId(), d.getAssetId(),
                d.getAmount(), d.getStatus(), d.getPayOrderNo(), d.getForfeitReason(),
                d.getCreatedAt(), d.getUpdatedAt());
    }
}
