package com.claw.server.domain.sharedpool;

import com.claw.server.common.enums.OwnershipStatus;
import com.claw.server.common.enums.OwnershipType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 资产产权记录（对应 V12 claw.asset_ownership）。
 * 全款购买后的产权凭证，追踪资产从购买到回收的全生命周期。
 * 修改5：资产使用寿命不设限，产权永久归所有人。
 */
@Entity
@Table(name = "asset_ownership", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetOwnership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    @Column(nullable = false)
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OwnershipType ownershipType;

    @Column(nullable = false)
    private BigDecimal purchasePrice;

    @Column(nullable = false)
    private LocalDate purchaseDate;

    private String purchaseOrderNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OwnershipStatus status = OwnershipStatus.ACTIVE;

    private Long recoveryOrderId;
    private Long newAssetId;

    @Builder.Default
    private Long tenantId = 1L;

    @Builder.Default
    private Boolean deleted = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
