package com.claw.server.domain.iot;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 光伏组件（对应 claw.pv_modules，V112 表）：组件级溯源主体。
 *
 * <p>serial_no 为组件身份证（唯一），支撑「这块板子哪来的、装在哪、发了多少电」三问：
 * 来源（product/sku/manufacturer/batch）、位置（station/string/position/inverter）、
 * 发电（经 inverter_device_no 关联 {@link PvGenerationHourly}）。
 */
@Entity
@Table(name = "pv_modules", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PvModule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属电站资产 ID。 */
    @Column(name = "station_asset_id")
    private Long stationAssetId;

    /** 组件序列号（唯一，组件身份证）。 */
    @Column(name = "serial_no", nullable = false, unique = true, length = 64)
    private String serialNo;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "sku_id")
    private Long skuId;

    @Column(name = "manufacturer_id")
    private Long manufacturerId;

    @Column(name = "batch_no", length = 64)
    private String batchNo;

    /** 标称峰值功率 W。 */
    @Column(name = "pmax_w", precision = 8, scale = 2)
    private BigDecimal pmaxW;

    /** 年衰减率（小数，如 0.0050 = 0.5%/年）。 */
    @Column(name = "degradation_rate", precision = 5, scale = 4)
    private BigDecimal degradationRate;

    private LocalDate installedAt;

    /** 所属组串。 */
    @Column(name = "string_id", length = 32)
    private String stringId;

    /** 组内位置序号（PG 中 position 为非保留关键字，可直接作列名）。 */
    @Column(name = "position")
    private Integer position;

    /** 所接逆变器设备号（关联小时电量）。 */
    @Column(name = "inverter_device_no", length = 64)
    private String inverterDeviceNo;

    /** 出厂/认证证书。 */
    @Column(name = "certificate_id")
    private Long certificateId;

    /** EL 隐裂检测图。 */
    @Column(name = "el_image_url", length = 255)
    private String elImageUrl;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
