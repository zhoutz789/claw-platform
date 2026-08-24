package com.claw.server.domain.asset;

import com.claw.server.common.enums.VehicleOpType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/** 车辆营收运营记录（客运/物流/流动售卖/广告/录像）。对应 claw.asset_vehicle_ops。 */
@Entity
@Table(name = "asset_vehicle_ops", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetVehicleOps {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleOpType opType;

    @Column(nullable = false)
    @Builder.Default
    private Instant startedAt = Instant.now();

    private Instant endedAt;

    @Builder.Default
    private BigDecimal revenue = BigDecimal.ZERO;

    @Column(columnDefinition = "text")
    private String detailJson;

    private String note;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
