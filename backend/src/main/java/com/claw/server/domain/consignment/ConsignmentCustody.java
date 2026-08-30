package com.claw.server.domain.consignment;

import com.claw.server.common.enums.CustodyStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 寄售占有权（法律真源，对应 V49 claw.consignment_custodies）。
 * 与 inventory 1:1；记录设备在某服务站的寄售占有、在途责任方、扫码交接时点（Q2 占有权转移点）。
 */
@Entity
@Table(name = "consignment_custodies", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsignmentCustody {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, unique = true)
    private Long deviceId;

    @Column(name = "manufacturer_id", nullable = false)
    private Long manufacturerId;

    @Column(name = "holder_station_id")
    private Long holderStationId;

    /** ACTIVE / TRANSFERRED_OUT / RETURNED / RELEASED。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CustodyStatus status = CustodyStatus.ACTIVE;

    /** 在途责任方：SOURCE_STATION / MANUFACTURER / LOGISTICS。 */
    @Column(name = "liability_holder", length = 20)
    private String liabilityHolder;

    @Column(name = "transferred_at")
    private Instant transferredAt;

    @Column(name = "transfer_order_id")
    private Long transferOrderId;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "ended_reason", length = 40)
    private String endedReason;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
