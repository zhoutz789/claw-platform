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

    /**
     * 占有主体设备。
     *
     * <p>不做「全表唯一」：占有权流转（站间调拨收货）会为同一设备留下历史行——旧行写
     * {@code ended_at/ended_reason} 关闭、新行接管。全局唯一会让调拨收货插不进去。
     * 由 V63 的部分唯一索引 {@code uq_cc_device_active (device_id) WHERE ended_at IS NULL}
     * 守住「一台设备同时只有一个有效占有权」这条核心不变式。
     */
    @Column(name = "device_id", nullable = false)
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
