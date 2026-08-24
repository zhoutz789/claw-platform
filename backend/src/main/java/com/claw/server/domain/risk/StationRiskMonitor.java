package com.claw.server.domain.risk;

import com.claw.server.common.enums.RiskMonitorStatus;
import com.claw.server.common.enums.RiskMetricType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "station_risk_monitor", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationRiskMonitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long stationId;

    private Long operatorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskMetricType metricType;

    private BigDecimal metricValue;
    private BigDecimal threshold;
    private BigDecimal baseline;

    @Column(nullable = false)
    @Builder.Default
    private Integer riskScore = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RiskMonitorStatus status = RiskMonitorStatus.NORMAL;

    private Instant triggeredAt;
    private String triggeredReason;
    private Instant resolvedAt;
    private Long resolvedBy;
    private String resolutionNote;

    private Long riskEventId;

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
