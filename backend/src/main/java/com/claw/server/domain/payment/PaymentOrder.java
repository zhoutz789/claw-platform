package com.claw.server.domain.payment;

import com.claw.server.common.enums.PayStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 支付单（对应 claw.payment_orders，V1 定义 + V7 增列 escrow_type）。
 * 状态机见 {@link PayStatus}；escrow_type 非空时表示三专户定向收单。
 */
@Entity
@Table(name = "payment_orders", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNo;

    /** 关联业务单号（充值/换电单/订单等）。 */
    @Column(nullable = false)
    private String bizRef;

    @Column(nullable = false)
    private BigDecimal amountUsd;

    /** 下单时刻汇率快照（USD→KHR）。 */
    private BigDecimal khrRateSnapshot;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String paymentMethod = "KHQR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private PayStatus status = PayStatus.CREATED;

    private Instant paidAt;

    /** 三专户收单目标：RESIDUAL_RESERVE/BATTERY_FUND/VEHICLE_RISK；null=普通收单。 */
    private String escrowType;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
