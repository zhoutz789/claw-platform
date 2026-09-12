package com.claw.server.domain.ocpp;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * OCPP 充电子事务（对应 claw.ocpp_transactions，V118 表）。
 *
 * <p>StartTransaction / StopTransaction 落库；累计电量 = stop_wh − start_wh（计量铁律：
 * 以电表累计 Wh 计数器为准，禁用功率积分 / SOC 差值）。关联 ChargeSession 做合规结算。
 */
@Entity
@Table(name = "ocpp_transactions", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OcppTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_id", nullable = false, length = 64)
    private String stationId;

    @Column(name = "connector_id", nullable = false)
    private int connectorId;

    @Column(name = "id_tag", length = 64)
    private String idTag;

    @Column(name = "start_wh")
    private BigDecimal startWh;

    @Column(name = "stop_wh")
    private BigDecimal stopWh;

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "stop_at")
    private Instant stopAt;

    /** 本次累计电量 Wh（= stop_wh − start_wh）。 */
    @Column(name = "meter_wh")
    private BigDecimal meterWh;

    /** INPROGRESS / COMPLETED。 */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "INPROGRESS";

    /** OCPP 事务号（StartTransaction 返回）。 */
    @Column(name = "transaction_id")
    private Integer transactionId;

    /** 关联 assets.id（结算溯源）。 */
    private Long assetId;
}
