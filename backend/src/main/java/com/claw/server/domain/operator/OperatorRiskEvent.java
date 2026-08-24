package com.claw.server.domain.operator;

import com.claw.server.common.enums.AutoAction;
import com.claw.server.common.enums.RiskEventType;
import com.claw.server.common.enums.RiskSeverity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "operator_risk_events", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperatorRiskEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long operatorId;

    private Long stationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskSeverity severity;

    @Column(nullable = false)
    private String description;

    private BigDecimal detectedValue;
    private BigDecimal expectedValue;

    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Enumerated(EnumType.STRING)
    private AutoAction autoAction;

    @Column(nullable = false)
    @Builder.Default
    private Boolean actionTaken = false;

    private Instant actionAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean resolved = false;

    private Long resolvedBy;
    private Instant resolvedAt;
    private String resolutionNote;

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
