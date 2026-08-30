package com.claw.server.domain.merchant;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 铺位（对应 V52 claw.merchant_booths）。
 * 区块下的具体可招商铺位；承租方（tenant_user_id）在 Phase 2 招商签约后回填。
 * UNIQUE(zone_id, booth_code)。
 */
@Entity
@Table(name = "merchant_booths", schema = "claw",
        uniqueConstraints = @UniqueConstraint(columnNames = {"zone_id", "booth_code"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantBooth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    @Column(name = "booth_code", nullable = false, length = 64)
    private String boothCode;

    @Column(length = 160)
    private String name;

    @Column(name = "area_sqm", precision = 10, scale = 2)
    private BigDecimal areaSqm;

    @Column(name = "monthly_rent", precision = 12, scale = 2)
    private BigDecimal monthlyRent;

    /** AVAILABLE / RESERVED / LEASED。 */
    @Column(nullable = false, length = 24)
    @Builder.Default
    private String status = "AVAILABLE";

    @Column(name = "tenant_user_id")
    private Long tenantUserId;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
