package com.claw.server.domain.vpp;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 虚拟电厂调度指令（对应 claw.vpp_dispatch_orders，V113 表）。
 *
 * <p>{@code shadow=true} 表示影子模式：只生成建议并留痕，<b>不调用任何下发通道</b>
 * （DeviceCommandService / MQTT gateway）。影子开关读 system_config 的
 * {@code VPP_SHADOW_MODE}，缺省 {@code true}（安全默认值：宁可不控，不可误控）。
 */
@Entity
@Table(name = "vpp_dispatch_orders", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VppDispatchOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long portfolioId;

    @Column(nullable = false)
    private Long resourceId;

    /** DERATE_PV / CHARGE_ESS / DISCHARGE_ESS / CURTAIL_CHARGER / START_GEN。 */
    @Column(name = "command_type", nullable = false, length = 32)
    private String commandType;

    @Column(name = "target_w")
    private BigDecimal targetW;

    private Instant startAt;
    private Instant endAt;

    /** ISSUED / ACKED / EXECUTING / DONE / FAILED / EXPIRED。 */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "ISSUED";

    /** 影子标记：true=仅建议留痕，未真实下发。 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean shadow = false;

    private Long issuedBy;

    @Column(length = 255)
    private String reason;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
