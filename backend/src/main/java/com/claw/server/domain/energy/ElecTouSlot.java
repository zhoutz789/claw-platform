package com.claw.server.domain.energy;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * TOU 峰谷电价时段（对应 claw.elec_tou_slots，V114 表）。
 *
 * <p>{@code start_minute / end_minute} 为「当日 0 点起的分钟数」（0–1439），
 * 不用 {@code TIME} 类型以避免时区歧义；允许 {@code start_minute > end_minute} 表示跨零点时段。
 *
 * <p><b>注意：</b>电价数值目前是<b>占位符</b>。服务层在取不到有效时段时返回 {@code null}
 * 而不是猜一个默认值——宁可保守，不能造假价格。
 */
@Entity
@Table(name = "elec_tou_slots", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ElecTouSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PEAK / FLAT / VALLEY。 */
    @Column(name = "slot_code", nullable = false, length = 32)
    private String slotCode;

    /** 季节标签，默认 ALL。 */
    @Column(name = "season_tag", nullable = false, length = 16)
    @Builder.Default
    private String seasonTag = "ALL";

    @Column(name = "start_minute", nullable = false)
    private Integer startMinute;

    @Column(name = "end_minute", nullable = false)
    private Integer endMinute;

    /** 电度电价 $/kWh。 */
    @Column(name = "energy_price", nullable = false, precision = 18, scale = 8)
    private BigDecimal energyPrice;

    /** 需量电价 $/kW（柬埔寨工商业按最大需量计费）。 */
    @Column(name = "demand_price", precision = 18, scale = 8)
    private BigDecimal demandPrice;

    /** NULL = 一直有效。 */
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /** 时段重叠时取小者。 */
    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
