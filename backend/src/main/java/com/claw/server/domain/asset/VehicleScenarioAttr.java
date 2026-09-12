package com.claw.server.domain.asset;

import jakarta.persistence.*;
import lombok.*;

/**
 * 车辆产品类的场景属性定义（每种车型的差异化录入项）。
 * 复用 {@code asset_attr_defs} 思路，但归属到具体产品类，
 * 新增第 N 种车型的属性 = 插 N 行本表，零代码。
 * 对应 claw.vehicle_scenario_attrs（V122）。
 */
@Entity
@Table(name = "vehicle_scenario_attrs", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleScenarioAttr {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_class_id", nullable = false)
    private Long productClassId;

    @Column(name = "attr_key", nullable = false)
    private String attrKey;

    @Column(name = "attr_type", nullable = false)
    private String attrType;

    @Column(name = "unit")
    private String unit;

    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private Boolean required = false;

    @Column(name = "label_zh", nullable = false)
    @Builder.Default
    private String labelZh = "";

    @Column(name = "label_en", nullable = false)
    @Builder.Default
    private String labelEn = "";

    @Column(name = "label_km", nullable = false)
    @Builder.Default
    private String labelKm = "";

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}
