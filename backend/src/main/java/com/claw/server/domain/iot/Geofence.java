package com.claw.server.domain.iot;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 通用电子围栏（对应 claw.geofences）。
 *
 * <p>几何模式照搬 V29 airspace_zones（center/radius 或 polygon_wkt），去无人机专用化，
 * 支持任意属主（PRODUCT/DEVICE/ASSET/PROJECT）。
 * 越界判定由 {@code GeofenceService} 完成：RADIUS 完整支持，POLYGON 走 WKT 点在多边形内判定。
 */
@Entity
@Table(name = "geofences", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Geofence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PRODUCT / DEVICE / ASSET / PROJECT */
    @Column(name = "owner_type", nullable = false, length = 16)
    private String ownerType;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** RADIUS / POLYGON */
    @Column(name = "fence_type", nullable = false, length = 16)
    private String fenceType;

    @Column(name = "center_lat", precision = 10, scale = 7)
    private BigDecimal centerLat;

    @Column(name = "center_lng", precision = 10, scale = 7)
    private BigDecimal centerLng;

    @Column(name = "radius_m")
    private Integer radiusM;

    /** POLYGON 类型填，WKT: POLYGON((lng lat, lng lat, ...)) */
    @Column(name = "polygon_wkt", columnDefinition = "text")
    private String polygonWkt;

    /** ENTER / EXIT / INTRUSION（→ ALERT / LOCK） */
    @Column(name = "trigger_action", nullable = false, length = 16)
    @Builder.Default
    private String triggerAction = "ALERT";

    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private String status = "ENABLED";

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private Long tenantId = 1L;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
