package com.claw.server.domain.iot;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 最新遥测（对应 claw.telemetry_latest，V8 表）。
 * Redis 热数据 + PG 落库；每个设备仅一条最新记录。
 */
@Entity
@Table(name = "telemetry_latest", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TelemetryLatest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long deviceId;

    @Column(nullable = false)
    private Long assetId;

    private BigDecimal speed;   // km/h

    private BigDecimal soc;     // %

    private BigDecimal temp;    // ℃

    private BigDecimal humid;   // %

    /** 故障码列表（JSON）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    private String faults;

    private BigDecimal lat;

    private BigDecimal lng;

    @Column(nullable = false)
    @Builder.Default
    private Instant reportedAt = Instant.now();

    @Builder.Default
    private Long tenantId = 1L;
}
