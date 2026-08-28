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

    // ===== V38：主部件更换留痕（F7.4 / F16.5）=====
    private String componentType;    // MOTOR/BATTERY/CONTROLLER/REMOTE/CHARGER...
    private String oldComponentNo;   // 更换前编号
    private String newComponentNo;   // 更换后编号

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
