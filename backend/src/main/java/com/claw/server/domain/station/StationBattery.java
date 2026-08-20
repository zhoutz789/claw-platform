package com.claw.server.domain.station;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 换电站电池位（对应 claw.station_batteries，V6 表）。
 * READY 满电可换 | CHANGING 充电中 | OUT 出库（被用户持有）。
 * 满电电池数 = 换电站可用供给（地图适配层数据源）。
 */
@Entity
@Table(name = "station_batteries", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationBattery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long stationId;

    /** 电池资产 id（assets.id）。 */
    @Column(nullable = false, unique = true)
    private Long batteryId;

    @Column(nullable = false)
    private Integer slotNo;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "CHARGING";

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal soc = new BigDecimal("100.00");

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
