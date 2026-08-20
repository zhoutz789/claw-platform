package com.claw.server.domain.payment;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 日终对账批次（对应 claw.reconciliation_runs，V7 定义）。
 * 技术文档 4.1：每日 T+1 拉取 ABA 流水与账本分录双向对账，差异告警人工处理。
 */
@Entity
@Table(name = "reconciliation_runs", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate runDate;

    /** RUNNING | MATCHED | MISMATCH。 */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "RUNNING";

    /** 平台账本当日资金净额（充值为正、提现为负）。 */
    @Column(nullable = false)
    @Builder.Default
    private BigDecimal platformTotal = BigDecimal.ZERO;

    /** ABA 流水当日资金净额。 */
    @Column(nullable = false)
    @Builder.Default
    private BigDecimal bankTotal = BigDecimal.ZERO;

    @Builder.Default
    private Integer matchedCount = 0;

    @Builder.Default
    private Integer mismatchCount = 0;

    /** 差异明细（JSON：单据号/金额/方向）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    private String detailJson;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
