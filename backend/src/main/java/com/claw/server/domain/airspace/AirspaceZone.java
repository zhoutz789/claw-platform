package com.claw.server.domain.airspace;

import jakarta.persistence.*;
import com.claw.server.common.enums.AirspaceLevel;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 空域分区（对应 claw.airspace_zones）。
 * 地理围栏：OPERATIONAL 可飞作业区 / RESTRICTED 限飞区 / NFZ 禁飞区。
 * 无人机飞行计划须落在 OPERATIONAL 且不得与 NFZ 相交（合规前置）。
 */
@Entity
@Table(name = "airspace_zones", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AirspaceZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** OPERATIONAL 可飞 / RESTRICTED 限飞 / NFZ 禁飞。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AirspaceLevel level;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal centerLat;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal centerLng;

    /** 半径（米）。真库列名 radius_m（V29），必须显式声明：交由 Hibernate 隐式命名会推成 radiusm。 */
    @Column(name = "radius_m", nullable = false)
    private Integer radiusM;

    @Column(length = 8)
    @Builder.Default
    private String country = "KH";

    private String note;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
