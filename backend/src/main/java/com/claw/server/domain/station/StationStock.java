package com.claw.server.domain.station;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 站点现货库存（对应 claw.station_stock）。
 * 投资者认购车辆投放至站点成为现货；客户在站点选购/换电消耗。
 * sku_code 为厂家产品 SKU（V6 factory_products 后建外键引用）。
 */
@Entity
@Table(name = "station_stock", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long stationId;

    @Column(nullable = false)
    private String skuCode;

    @Column(nullable = false)
    @Builder.Default
    private Integer stockQty = 0;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
