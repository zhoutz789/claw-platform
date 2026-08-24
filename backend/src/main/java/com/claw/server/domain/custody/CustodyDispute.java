package com.claw.server.domain.custody;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "custody_disputes", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustodyDispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long transferId;

    @Column(nullable = false)
    private Long assetId;

    @Column(nullable = false)
    private Long claimantId;

    @Column(nullable = false)
    private Long respondentId;

    @Column(nullable = false)
    private String disputeType;

    @Column(nullable = false)
    private String description;

    @Column(columnDefinition = "jsonb")
    private String evidenceUrls;

    private BigDecimal claimAmount;
    private BigDecimal awardedAmount;

    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING";

    private Long arbitratorId;
    private String resolution;
    private Instant resolvedAt;

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
