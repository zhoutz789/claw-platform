package com.claw.server.domain.insurance;

import com.claw.server.common.enums.InsuranceStatus;
import com.claw.server.common.enums.InsuranceType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "vehicle_insurance", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleInsurance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String policyNo;

    @Column(nullable = false)
    private Long assetId;

    @Column(nullable = false)
    private Long ownerUserId;

    @Column(nullable = false)
    private Long templateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InsuranceType insuranceType;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private BigDecimal coverageAmount;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal deductible = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal premiumMonthly;

    private LocalDate premiumPaidThrough;

    @Column(nullable = false)
    private LocalDate startDate;

    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private InsuranceStatus status = InsuranceStatus.ACTIVE;

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
