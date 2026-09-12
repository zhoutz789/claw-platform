package com.claw.server.domain.ocpp;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 充电桩连接器（对应 claw.ocpp_connectors，V118 表）。
 *
 * <p>一个站点可含多个连接器；单枪桩 connectorId 通常为 1。连接器状态映射到
 * {@code telemetry_latest}，用于看板在线/故障/空闲展示。
 */
@Entity
@Table(name = "ocpp_connectors", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChargingConnector {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_id", nullable = false, length = 64)
    private String stationId;

    @Column(name = "connector_id", nullable = false)
    private int connectorId;

    /** 连接器级资产（一般复用站点资产）。 */
    private Long assetId;

    /** Available / Occupied / Faulted / Unavailable / Charging。 */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "UNAVAILABLE";

    /**
     * 额定最大功率 W。
     * <b>必须显式声明 @Column</b>：字段名以大写字母 W 结尾，Hibernate 隐式命名会推成
     * {@code maxpowerw}，与 Flyway 建表的 {@code max_power_w} 不一致（ArchitectureBoundaryTest 强制）。
     */
    @Column(name = "max_power_w")
    private BigDecimal maxPowerW;

    /** 最近累计电表读数 Wh。 */
    @Column(name = "last_meter_wh")
    private BigDecimal lastMeterWh;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
