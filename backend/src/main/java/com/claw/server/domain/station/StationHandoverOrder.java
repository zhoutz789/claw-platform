package com.claw.server.domain.station;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 服务站扫码收发单（对应 claw.station_handover_orders，V7 表）。
 * 站方扫码发放满电电池（OUT）/ 回收欠电电池（IN），与换电单可关联可独立。
 */
@Entity
@Table(name = "station_handover_orders", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationHandoverOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String handoverNo;

    @Column(nullable = false)
    private Long stationId;

    @Column(nullable = false)
    private Long batteryId;

    /** OUT 发放 | IN 回收。 */
    @Column(nullable = false, length = 8)
    private String opType;

    /** 关联换电单（可空，独立收发不关联）。 */
    private String orderNo;

    private Long operatorId;

    /** 回收时 BMS 上报电量 %。 */
    private BigDecimal soc;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "DONE";

    private String memo;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
