package com.claw.server.domain.payment;

import com.claw.server.common.enums.WalletTxnStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 钱包交易单（对应 claw.wallet_txns，V7 定义）：充值 / 提现。
 * 状态机见 {@link WalletTxnStatus}。
 */
@Entity
@Table(name = "wallet_txns", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WalletTxn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String txnNo;

    @Column(nullable = false)
    private Long userId;

    /** RECHARGE | WITHDRAW。 */
    @Column(nullable = false, length = 16)
    private String txnType;

    @Column(nullable = false)
    private BigDecimal amountUsd;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal feeUsd = BigDecimal.ZERO;

    @Column(nullable = false, length = 32)
    @Builder.Default
    private String channel = "khqr";

    /** 关联支付单号（KHQR 收单）。 */
    private String paymentOrderNo;

    /** ABA 侧流水号（入金/出金回执）。 */
    private String abaRef;

    /** 提现收款账户信息（JSON）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    private String bankAccountJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private WalletTxnStatus status = WalletTxnStatus.CREATED;

    private String failReason;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
