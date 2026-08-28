package com.claw.server.domain.manufacturer;

import com.claw.server.common.enums.AssetType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/** 商品（厂家发布，对应某类资产：车辆/电池等）。对应 claw.products。 */
@Entity
@Table(name = "products", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long manufacturerId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetType assetType;

    private String model;
    private String description;

    private String brand;
    private String category;

    @Column(name = "params_json")
    private String paramsJson;

    @Column(name = "cover_images_json")
    private String coverImagesJson;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(name = "video_url")
    private String videoUrl;

    @Column(name = "live_enabled", nullable = false)
    @Builder.Default
    private boolean liveEnabled = false;

    @Column(name = "live_url")
    private String liveUrl;

    @Column(name = "share_code", unique = true)
    private String shareCode;

    @Column(name = "reward_rate", nullable = false)
    @Builder.Default
    private BigDecimal rewardRate = BigDecimal.ZERO;

    /** 每产品上报间隔(秒)，时序要求各异（点 3）。 */
    @Column(name = "report_interval_seconds")
    private Integer reportIntervalSeconds;

    /** 扩展属性值(JSON)，与 params_json 并存（点 2 模板字段值）。 */
    @Column(name = "attr_json", columnDefinition = "text")
    private String attrJson;

    @Column(nullable = false)
    @Builder.Default
    private String status = "ON_SALE";

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Builder.Default
    private Boolean deleted = false;
}
