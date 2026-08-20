package com.claw.server.domain.ledger;

import com.claw.server.common.enums.AccountType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 账户（对应 claw.accounts）。
 * 平台内部户 user_id 为空；用户/资产/三专户按 account_type 区分。
 * balance 受 DB CHECK (balance >= 0) 兜底，出账前应用层先校验。
 */
@Entity
@Table(name = "accounts", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;    // NULL = 平台内部户

    private Long assetId;   // 资产子账户时关联

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountType accountType;

    @Builder.Default
    @Column(nullable = false, length = 3)
    private String currency = "USD";

    @Builder.Default
    @Column(nullable = false)
    private BigDecimal balance = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false)
    private BigDecimal frozen = BigDecimal.ZERO;

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
