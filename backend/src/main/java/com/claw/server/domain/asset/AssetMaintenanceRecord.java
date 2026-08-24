package com.claw.server.domain.asset;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/** 资产维修记录。对应 claw.asset_maintenance_records。 */
@Entity
@Table(name = "asset_maintenance_records", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetMaintenanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    @Column(nullable = false)
    @Builder.Default
    private Instant servicedAt = Instant.now();

    private String mtype;   // 维修类型
    private String vendor;
    private BigDecimal cost;
    private String note;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
