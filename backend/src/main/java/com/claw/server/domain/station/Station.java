package com.claw.server.domain.station;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 站点（对应 claw.stations）：换电 / 现货交付网点。
 * 客户选购 = 附近站点现货（周老板验收口径）；country_code 关联法域。
 */
@Entity
@Table(name = "stations", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Station {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    private String area;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String countryCode = "KHM";

    private String province;
    private String city;
    private String district;

    private BigDecimal lat;
    private BigDecimal lng;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Builder.Default
    @Column(nullable = false, length = 32)
    private String openHours = "24H";

    private Long operatorId;

    @Builder.Default
    private Long tenantId = 1L;

    /** 关联商家（V52 商家入驻骨架：服务站 ↔ 商家入口，招商审批流 Phase 2 回填）。 */
    @Column(name = "merchant_id")
    private Long merchantId;

    @Builder.Default
    private Boolean deleted = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
