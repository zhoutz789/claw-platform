package com.claw.server.domain.custody;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "custody_transfer_audit", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustodyTransferAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long transferId;

    @Column(nullable = false)
    private Long assetId;

    private String anomalyType;
    private Integer riskScore;
    private String description;

    private Integer userDailyTransferCount;
    private Integer assetDailyTransferCount;

    @Column(nullable = false)
    @Builder.Default
    private Instant detectedAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Boolean reviewed = false;

    @Builder.Default
    private Long tenantId = 1L;

    @Builder.Default
    private Boolean deleted = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
