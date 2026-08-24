package com.claw.server.domain.insurance;

import com.claw.server.common.enums.ClaimStatus;
import com.claw.server.common.enums.ClaimType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "accident_claims", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccidentClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String claimNo;

    @Column(nullable = false)
    private Long insuranceId;

    @Column(nullable = false)
    private Long assetId;

    private Long rentalOrderId;

    @Column(nullable = false)
    private Long claimantUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimType claimType;

    @Column(nullable = false)
    private Instant accidentDate;

    private String accidentLocation;

    @Column(nullable = false)
    private String description;

    @Column(columnDefinition = "jsonb")
    private String evidenceUrls;

    private String policeReportNo;

    @Column(nullable = false)
    private BigDecimal damageAmount;

    private BigDecimal assessedAmount;
    @Builder.Default
    private BigDecimal deductibleApplied = BigDecimal.ZERO;
    private BigDecimal payoutAmount;

    @Column(nullable = false)
    @Builder.Default
    private String fundSource = "INSURANCE_FUND";

    private String ledgerTxnId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ClaimStatus status = ClaimStatus.FILED;

    private Long reviewedBy;
    private Instant reviewedAt;
    private String reviewNotes;

    @Column(nullable = false)
    @Builder.Default
    private Instant filedAt = Instant.now();

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
