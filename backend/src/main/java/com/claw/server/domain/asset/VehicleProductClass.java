package com.claw.server.domain.asset;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/**
 * 车辆产品类（配置驱动的车型剖面模板）。
 * 新增一种车型 = 插一行本表 + 若干 {@link VehicleScenarioAttr}，零代码。
 * 复用既有"产品（类）= 设备模版"物联网内核 + 资产类型注册表思路。
 * 对应 claw.vehicle_product_classes（V122）。
 */
@Entity
@Table(name = "vehicle_product_classes", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleProductClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @Column(name = "name_zh", nullable = false)
    private String nameZh;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "name_km", nullable = false)
    private String nameKm;

    @Column(name = "scenario", nullable = false)
    private String scenario;

    /** 自动驾驶等级（NONE / ASSISTED / FULL），由 V126 增加；FULL 车型建档时自动挂载自主模块。 */
    @Column(name = "autonomy_level", nullable = false)
    @Builder.Default
    private String autonomyLevel = "NONE";

    /** CSV 能力标签，引用 {@code AssetCapability} 枚举 code。 */
    @Column(name = "capability_tags")
    private String capabilityTags;

    /** CSV 默认设备组合（VEHICLE_TCU / BMS / CAMERA / AD_SCREEN ...）。 */
    @Column(name = "default_device_types")
    private String defaultDeviceTypes;

    /** JSONB 结构化属性 schema（可选，前端按此渲染录入表单）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attr_schema", columnDefinition = "jsonb")
    private String attrSchema;

    /** CSV 所需证件（营运证 / 合格证 / 一致性证书 ...）。 */
    @Column(name = "required_certs")
    private String requiredCerts;

    /** JSONB 场景默认围栏 / 路网模板。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "geofence_preset", columnDefinition = "jsonb")
    private String geofencePreset;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
