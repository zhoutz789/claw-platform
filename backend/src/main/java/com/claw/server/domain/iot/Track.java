package com.claw.server.domain.iot;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 轨迹点（对应 claw.tracks，V8 表）。
 * 生产环境用 TimescaleDB hypertable 自动分区压缩（7天热/90天温/归档冷）。
 */
@Entity
@Table(name = "tracks", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Track {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long deviceId;

    @Column(nullable = false)
    private Long assetId;

    @Column(nullable = false)
    private Instant ts;

    private BigDecimal lat;

    private BigDecimal lng;

    private BigDecimal speed;

    private BigDecimal soc;
}
