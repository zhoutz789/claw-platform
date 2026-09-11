package com.claw.server.domain.vpp;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 虚拟电厂资源（对应 claw.vpp_resources，V113 表）。
 *
 * <p>一个资产只能注册成一个资源（唯一索引 asset_id），重复注册返回既有记录而非报错。
 * 资源类型：{@code PV / ESS / CHARGER / DIESEL_GEN / CONTROLLABLE_LOAD}。
 *
 * <p>注：以下以大写字母结尾的字段必须显式声明 {@code @Column}，否则 Hibernate 隐式命名
 * 会推成全小写（{@code ratedPowerW → ratedpowerw}），与 Flyway 下划线列名不一致
 * （ArchitectureBoundaryTest 强制）。
 */
@Entity
@Table(name = "vpp_resources", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VppResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long portfolioId;

    @Column(nullable = false, unique = true)
    private Long assetId;

    /** PV / ESS / CHARGER / DIESEL_GEN / CONTROLLABLE_LOAD。 */
    @Column(name = "resource_type", nullable = false, length = 24)
    private String resourceType;

    @Column(name = "rated_power_w")
    private BigDecimal ratedPowerW;

    /** 可调下限 W（可为负：储能充电方向）。 */
    @Column(name = "adjustable_min_w")
    private BigDecimal adjustableMinW;

    /** 可调上限 W。 */
    @Column(name = "adjustable_max_w")
    private BigDecimal adjustableMaxW;

    /** 响应时延（秒）。 */
    private Integer responseSeconds;

    private String gridNode;

    /** ONLINE / OFFLINE / FAULT。 */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "ONLINE";

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
