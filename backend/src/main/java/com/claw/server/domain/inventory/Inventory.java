package com.claw.server.domain.inventory;

import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.OwnershipType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 运营库存台账（对应 V48 claw.inventory，每资产一行）。
 * 寄售占有权真源见 {@code CustodyRecord}（custody_records）；本表为查询友好的冗余台账（与 custody 1:1）。
 * 设备在本平台 = assets + devices，库存以资产(asset)为粒度。
 */
@Entity
@Table(name = "inventory", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id")
    private Long assetId;

    /** 库存运营主体（本域业务代码以 device 为粒度）。 */
    @Column(name = "device_id")
    private Long deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ownership_type", nullable = false)
    private OwnershipType ownershipType;

    @Column(name = "owner_manufacturer_id")
    private Long ownerManufacturerId;

    @Column(name = "holder_station_id")
    private Long holderStationId;

    @Column(name = "custody_id")
    private Long custodyId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "serial_number")
    private String serialNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false)
    private LifecycleStatus currentStatus;

    @Column(name = "inbound_at")
    private Instant inboundAt;

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
