package com.claw.server.domain.asset;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 无人机机型产品类（配置驱动的机型剖面 + 作业模版）。
 * 新增一种机型 = 插一行本表 + 若干 {@link DroneScenarioAttr}，零代码。
 * 镜像 {@link VehicleProductClass}，新增航空维度：{@code opsTemplate}（作业模版）与
 * {@code dockSupported}（是否适配 DJI Dock 3 自动机场）。
 * 对应 claw.drone_product_classes（V137）。
 */
@Entity
@Table(name = "drone_product_classes", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DroneProductClass {

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

    /** 作业场景（引用 {@code DroneScenario} 名，如 AGRICULTURE / INSPECTION / GENERAL）。 */
    @Column(name = "scenario", nullable = false)
    private String scenario;

    /** CSV 能力标签。 */
    @Column(name = "capability_tags")
    private String capabilityTags;

    /** CSV 默认设备组合（DRONE_FCU / CAMERA ...）。 */
    @Column(name = "default_device_types")
    private String defaultDeviceTypes;

    /** JSONB 结构化属性 schema（可选，前端按此渲染录入表单）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attr_schema", columnDefinition = "jsonb")
    private String attrSchema;

    /** CSV 所需证件（SSCA 适航证 / 飞行许可 ...）。 */
    @Column(name = "required_certs")
    private String requiredCerts;

    /** JSONB 作业模版（SPRAY / CARGO / INSPECTION / RESCUE ...）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ops_template", columnDefinition = "jsonb")
    private String opsTemplate;

    /** 是否适配自动机场（DJI Dock 3）。 */
    @Column(name = "dock_supported", nullable = false)
    @Builder.Default
    private Boolean dockSupported = false;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
