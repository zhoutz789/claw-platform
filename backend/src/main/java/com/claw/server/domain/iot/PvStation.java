package com.claw.server.domain.iot;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 光伏电站扩展（对应 claw.pv_stations，V111 表）。
 *
 * <p>PV_STATION 资产此前无专属扩展工厂（{@code AssetService} 只有 vehicle/battery/drone 三套），
 * 本实体补齐电站差异化字段。{@code ratedPowerWp} 是铭牌装机容量，为 PR（性能比）的分母来源；
 * 建档时若无铭牌输入则留空，此时 PR 一律返回 null（不编造分母）。
 */
@Entity
@Table(name = "pv_stations", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PvStation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对应 PV_STATION 资产 ID（唯一：一个资产一行）。 */
    @Column(name = "asset_id", nullable = false, unique = true)
    private Long assetId;

    /** 铭牌装机容量 Wp（PR 分母）。 */
    @Column(name = "rated_power_wp", precision = 12, scale = 2)
    private BigDecimal ratedPowerWp;

    /** 并网编号 / 购售电合同号。 */
    @Column(name = "grid_connection_no", length = 64)
    private String gridConnectionNo;

    private LocalDate installedAt;

    /** 安装倾角（度）。 */
    @Column(name = "tilt_deg", precision = 5, scale = 2)
    private BigDecimal tiltDeg;

    /** 方位角（度，0=正北，180=正南）。 */
    @Column(name = "azimuth_deg", precision = 6, scale = 2)
    private BigDecimal azimuthDeg;

    /** 组件总块数。 */
    private Integer moduleCount;

    /** 运维方 / 业主。 */
    private Long operatorId;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
