package com.claw.server.domain.merchant;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 商家区块/招商片区（对应 V52 claw.merchant_zones）。
 * 商家下的招商片区，可关联一个服务站（可选）。UNIQUE(merchant_id, zone_code)。
 */
@Entity
@Table(name = "merchant_zones", schema = "claw",
        uniqueConstraints = @UniqueConstraint(columnNames = {"merchant_id", "zone_code"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "zone_code", nullable = false, length = 64)
    private String zoneCode;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "station_id")
    private Long stationId;

    /** OPEN / LOCKED。 */
    @Column(nullable = false, length = 24)
    @Builder.Default
    private String status = "OPEN";

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
