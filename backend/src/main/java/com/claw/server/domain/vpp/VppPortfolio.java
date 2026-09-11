package com.claw.server.domain.vpp;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 虚拟电厂（对应 claw.vpp_portfolios，V113 表）。
 *
 * <p><b>业务前提：</b>柬埔寨没有需求响应/辅助服务市场，参与电网调度不产生收益，
 * 因此本 VPP 是「私域自用型」——聚合光伏/储能/充电桩/柴油机组做站内自用优化
 * （柴油替代 + 需量管理 + 自发自用最大化）。对外电网/DR 接口只保留服务层抽象。
 */
@Entity
@Table(name = "vpp_portfolios", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VppPortfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    private Long operatorId;

    private String regionCode;

    /** 并网点。 */
    private String gridNode;

    /** 目标自发自用率 0–1。 */
    private BigDecimal targetSelfConsumptionRate;

    /** ACTIVE / SUSPENDED / ARCHIVED。 */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
