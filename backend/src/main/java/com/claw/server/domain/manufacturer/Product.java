package com.claw.server.domain.manufacturer;

import com.claw.server.common.enums.AssetType;
import jakarta.persistence.*;
import lombok.*;

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
