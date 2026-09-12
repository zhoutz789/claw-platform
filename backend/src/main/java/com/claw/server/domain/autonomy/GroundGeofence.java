package com.claw.server.domain.autonomy;

import com.claw.server.common.enums.GeofenceLevel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/**
 * 平台自建地面围栏（作业区/禁行区/避让区），决定无人车路网与边界。
 * 对应 claw.ground_geofences（V121）。
 */
@Entity
@Table(name = "ground_geofences", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroundGeofence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GeofenceLevel level;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String polygonJson;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
