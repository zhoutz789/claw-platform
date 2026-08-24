package com.claw.server.domain.operator;

import com.claw.server.common.enums.BondStatus;
import com.claw.server.common.enums.OperatorType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "operator_bonds", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperatorBond {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long operatorId;

    private Long stationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperatorType operatorType;

    @Column(nullable = false)
    private BigDecimal baseBond;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal managedAssetValue = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal bondRate = BigDecimal.valueOf(0.05);

    @Column(nullable = false)
    private BigDecimal requiredBond;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal postedBond = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal shortfall = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BondStatus status = BondStatus.PENDING;

    private Long reviewedBy;
    private Instant reviewedAt;

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
