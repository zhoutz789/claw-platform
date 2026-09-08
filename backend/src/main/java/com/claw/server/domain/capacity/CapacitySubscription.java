package com.claw.server.domain.capacity;

import com.claw.server.common.enums.CapacitySubscriptionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 容量定购（对应 V71 claw.capacity_subscriptions）。
 * 用户认购 N 个容量单位，预付产能款（直付厂家托管，平台不经手资金池）。
 */
@Entity
@Table(name = "capacity_subscriptions", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapacitySubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long planId;

    @Column(nullable = false)
    private Long subscriberUserId;

    @Column(nullable = false)
    private Integer unitCount;

    @Column(nullable = false)
    private BigDecimal prepaidAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CapacitySubscriptionStatus status = CapacitySubscriptionStatus.ACTIVE;

    private String ledgerTxnId;

    /**
     * 付款完成时间（V81 新增）。
     *
     * <p>容量预定是「填份数 → 付款」一次完成：记账成功即视为已付款，落库时写 now()。
     */
    private Instant paidAt;

    @Builder.Default
    private Long tenantId = 1L;

    @Builder.Default
    private Boolean deleted = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
