package com.claw.server.domain.recovery;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 用户信用分（对应 V13 claw.claw_scores）。
 * Claw Score 0-1000，用于个人站准入/押金核定/风控阈值。
 */
@Entity
@Table(name = "claw_scores", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClawScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    @Builder.Default
    private Integer score = 650;

    @Column(nullable = false)
    @Builder.Default
    private String scoreLevel = "C";

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal onTimePaymentRate = BigDecimal.ONE;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal assetLossRate = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private Integer complaintCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer operatingMonths = 0;

    private String lastEventType;
    private String lastEventDetail;

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
