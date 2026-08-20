package com.claw.server.domain.credit;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 信用分变更事件（对应 claw.credit_score_events，V8 表）。
 * 每次分数变动写一条审计记录（delta / reason / refId）。
 */
@Entity
@Table(name = "credit_score_events", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditScoreEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Integer delta;

    /** 变更原因：SWAP_FREQUENCY | ON_TIME_PAYMENT | MILEAGE | REVIEW | INCOME_STABILITY | PENALTY。 */
    @Column(nullable = false, length = 64)
    private String reason;

    /** 关联业务单号（换电单/分期单等）。 */
    private String refId;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
