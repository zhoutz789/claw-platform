package com.claw.server.domain.ledger;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 复式记账分录（对应 claw.account_entries）。
 * 同一笔交易的所有分录共享 txnId；借贷必须平衡（LedgerService 强制）。
 * bizType + bizRef + accountId + direction 唯一，构成幂等键（DB 唯一索引兜底）。
 */
@Entity
@Table(name = "account_entries", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID txnId;

    @Column(nullable = false)
    private Long accountId;

    /** D 借（出账）| C 贷（入账）。 */
    @Column(nullable = false, length = 1)
    @Builder.Default
    private String direction = "C";

    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 32)
    private String bizType;

    private String bizRef;

    private String memo;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
