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
