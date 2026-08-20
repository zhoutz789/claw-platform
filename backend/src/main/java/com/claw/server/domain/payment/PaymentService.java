package com.claw.server.domain.payment;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.PaymentRequests;
import com.claw.server.common.dto.PaymentViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.PayStatus;
import com.claw.server.common.enums.WalletTxnStatus;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 支付服务（S4 核心）：ABA 托管资金路径。
 *
 * <p>技术文档 4.1 资金路径 = KHQR 收单 + ABA 受托账户 + Bakong 清算（零牌照运营）：
 * <ul>
 *   <li><b>充值</b>：创建支付单 + KHQR 收单码 → ABA 回调 → 对账校验 → 入账用户总账户
 *       （平台 MASTER 现金池借，用户 MASTER 贷）→ PAID；</li>
 *   <li><b>三专户收单</b>：KHQR 收单定向入账到残值准备金 / 电池基金 / 车辆风险金专户；</li>
 *   <li><b>提现</b>：账本扣减（用户 MASTER 借，平台 MASTER 贷）→ ABA 出金 →
 *       SUCCESS / FAILED_REFUND（失败退回账本）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    /** 三专户合法收单目标（与 AccountType 一致，D27 escrow 口径）。 */
    private static final Set<AccountType> ESCROW_TYPES = Set.of(
            AccountType.RESIDUAL_RESERVE, AccountType.BATTERY_FUND, AccountType.VEHICLE_RISK);

    private final PaymentOrderRepository paymentOrderRepository;
    private final WalletTxnRepository walletTxnRepository;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final AbaGateway abaGateway;

    // ------------------------------------------------------------------
    // 1. 充值：KHQR 收单（用户总账户）
    // ------------------------------------------------------------------
    @Transactional
    public PaymentViews.RechargeView recharge(Long userId, PaymentRequests.Recharge req) {
        String method = normalizeMethod(req.method());
        String orderNo = "PAY-" + shortId();
        String txnNo = "RCH-" + shortId();

        walletTxnRepository.save(WalletTxn.builder()
                .txnNo(txnNo).userId(userId).txnType("RECHARGE")
                .amountUsd(req.amount()).channel(method.toLowerCase())
                .paymentOrderNo(orderNo).status(WalletTxnStatus.CREATED).build());
        paymentOrderRepository.save(PaymentOrder.builder()
                .orderNo(orderNo).bizRef("RECHARGE:" + txnNo).amountUsd(req.amount())
                .channel(method.toLowerCase()).paymentMethod(method).status(PayStatus.CREATED).build());

        String khqr = abaGateway.createKhqr(orderNo, req.amount());
        log.info("充值收单 {} 金额 ${} 方式={}", orderNo, req.amount(), method);
        return new PaymentViews.RechargeView(orderNo, khqr, req.amount(), method, "CREATED");
    }

    // ------------------------------------------------------------------
    // 2. 三专户定向收单（KHQR 收单入账到专户）
    // ------------------------------------------------------------------
    @Transactional
    public PaymentViews.CollectView collect(Long userId, PaymentRequests.Collect req) {
        if (!ESCROW_TYPES.contains(req.escrowType())) {
            throw BizException.invalidParam("error.payment.escrow.invalid");
        }
        String orderNo = "COL-" + shortId();
        String txnNo = "COL-" + shortId();

        walletTxnRepository.save(WalletTxn.builder()
                .txnNo(txnNo).userId(userId).txnType("ESCROW_COLLECT")
                .amountUsd(req.amount()).channel("khqr")
                .paymentOrderNo(orderNo).status(WalletTxnStatus.CREATED).build());
        paymentOrderRepository.save(PaymentOrder.builder()
                .orderNo(orderNo).bizRef("COLLECT:" + txnNo).amountUsd(req.amount())
                .channel("khqr").paymentMethod("KHQR").status(PayStatus.CREATED)
                .escrowType(req.escrowType().name()).build());

        String khqr = abaGateway.createKhqr(orderNo, req.amount());
        log.info("三专户收单 {} 金额 ${} 目标={}", orderNo, req.amount(), req.escrowType());
        return new PaymentViews.CollectView(orderNo, khqr, req.amount(), req.escrowType().name(), "CREATED");
    }

    // ------------------------------------------------------------------
    // 3. ABA 回调：对账校验后入账（先对账后入账，防重复回调）
    // ------------------------------------------------------------------
    @Transactional
    public PaymentViews.WalletTxnView onCallback(String orderNo) {
        PaymentOrder order = paymentOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> BizException.notFound("error.payment.order.not.found"));
        if (order.getStatus() != PayStatus.CREATED) {
            throw BizException.of(40970, "error.payment.duplicate.callback");
        }
        WalletTxn txn = walletTxnRepository.findByPaymentOrderNo(orderNo)
                .orElseThrow(() -> BizException.notFound("error.payment.txn.not.found"));

        Account platform = accountService.getOrCreatePlatformAccount(AccountType.MASTER);
        if (order.getEscrowType() != null) {
            // 三专户收单：平台 MASTER 现金池 → 目标专户
            Account target = accountService.getOrCreatePlatformAccount(
                    AccountType.valueOf(order.getEscrowType()));
            ledgerService.postEntries(BizType.ESCROW_COLLECT, orderNo, List.of(
                    new LedgerRequests.Entry(platform.getId(), LedgerRequests.Direction.D,
                            order.getAmountUsd(), "三专户收单入账"),
                    new LedgerRequests.Entry(target.getId(), LedgerRequests.Direction.C,
                            order.getAmountUsd(), "三专户收单入账 " + order.getEscrowType())));
        } else {
            // 普通充值：平台 MASTER 现金池 → 用户 MASTER
            Account user = accountService.getOrCreateUserAccount(txn.getUserId());
            ledgerService.postEntries(BizType.RECHARGE, orderNo, List.of(
                    new LedgerRequests.Entry(platform.getId(), LedgerRequests.Direction.D,
                            order.getAmountUsd(), "充值入账"),
                    new LedgerRequests.Entry(user.getId(), LedgerRequests.Direction.C,
                            order.getAmountUsd(), "充值入账")));
        }

        order.setStatus(PayStatus.PAID);
        order.setPaidAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        paymentOrderRepository.save(order);

        txn.setStatus(WalletTxnStatus.PAID);
        txn.setUpdatedAt(Instant.now());
        walletTxnRepository.save(txn);

        log.info("支付回调入账 {} 金额 ${} escrow={}", orderNo, order.getAmountUsd(), order.getEscrowType());
        return toView(txn);
    }

    // ------------------------------------------------------------------
    // 4. 提现：账本扣减 → ABA 出金 → 成功（失败见 failWithdraw）
    // ------------------------------------------------------------------
    @Transactional
    public PaymentViews.WalletTxnView withdraw(Long userId, PaymentRequests.Withdraw req) {
        Account master = accountService.getOrCreateUserAccount(userId);
        if (master.getBalance().compareTo(req.amount()) < 0) {
            throw BizException.of(42260, "error.payment.balance.insufficient");
        }
        String txnNo = "WDR-" + shortId();
        Account platform = accountService.getOrCreatePlatformAccount(AccountType.MASTER);

        // 账本扣减（用户 MASTER 借，平台 MASTER 现金池贷）
        ledgerService.postEntries(BizType.WITHDRAW, txnNo, List.of(
                new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.D,
                        req.amount(), "提现扣减"),
                new LedgerRequests.Entry(platform.getId(), LedgerRequests.Direction.C,
                        req.amount(), "提现扣减")));

        String bankAccountJson = bankAccountJson(req);
        WalletTxn txn = walletTxnRepository.save(WalletTxn.builder()
                .txnNo(txnNo).userId(userId).txnType("WITHDRAW")
                .amountUsd(req.amount()).channel("aba_bank")
                .bankAccountJson(bankAccountJson).status(WalletTxnStatus.PROCESSING).build());

        // ABA 出金（模拟：直接成功返回流水号）
        String abaRef = abaGateway.payout(txnNo, req.amount(), bankAccountJson);
        txn.setAbaRef(abaRef);
        txn.setStatus(WalletTxnStatus.SUCCESS);
        txn.setUpdatedAt(Instant.now());
        walletTxnRepository.save(txn);

        log.info("提现 {} 金额 ${} ABA 流水={}", txnNo, req.amount(), abaRef);
        return toView(txn);
    }

    /** 提现出金失败：资金退回账本（平台 MASTER → 用户 MASTER），状态 FAILED_REFUND。 */
    @Transactional
    public PaymentViews.WalletTxnView failWithdraw(String txnNo, String reason) {
        WalletTxn txn = walletTxnRepository.findByTxnNo(txnNo)
                .orElseThrow(() -> BizException.notFound("error.payment.txn.not.found"));
        if (txn.getStatus() != WalletTxnStatus.PROCESSING) {
            throw BizException.of(40971, "error.payment.withdraw.status");
        }
        Account master = accountService.getOrCreateUserAccount(txn.getUserId());
        Account platform = accountService.getOrCreatePlatformAccount(AccountType.MASTER);
        ledgerService.postEntries(BizType.WITHDRAW, txnNo + ":REFUND", List.of(
                new LedgerRequests.Entry(platform.getId(), LedgerRequests.Direction.D,
                        txn.getAmountUsd(), "提现失败退回"),
                new LedgerRequests.Entry(master.getId(), LedgerRequests.Direction.C,
                        txn.getAmountUsd(), "提现失败退回")));

        txn.setStatus(WalletTxnStatus.FAILED_REFUND);
        txn.setFailReason(reason);
        txn.setUpdatedAt(Instant.now());
        walletTxnRepository.save(txn);

        log.info("提现 {} 出金失败退回：{}", txnNo, reason);
        return toView(txn);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------
    @Transactional(readOnly = true)
    public List<PaymentViews.WalletTxnView> listTxns(Long userId) {
        return walletTxnRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public PaymentViews.WalletTxnView getTxn(String txnNo) {
        return toView(walletTxnRepository.findByTxnNo(txnNo)
                .orElseThrow(() -> BizException.notFound("error.payment.txn.not.found")));
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------
    private String normalizeMethod(String method) {
        return method == null || method.isBlank() ? "KHQR" : method.toUpperCase();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String bankAccountJson(PaymentRequests.Withdraw req) {
        return "{\"bank\":\"" + (req.bankName() == null ? "ABA_BANK" : req.bankName())
                + "\",\"account\":\"" + req.bankAccount() + "\"}";
    }

    private PaymentViews.WalletTxnView toView(WalletTxn t) {
        return new PaymentViews.WalletTxnView(t.getTxnNo(), t.getUserId(), t.getTxnType(),
                t.getAmountUsd(), t.getFeeUsd(), t.getChannel(), t.getStatus().name(),
                t.getAbaRef(), t.getFailReason(), t.getCreatedAt());
    }
}
