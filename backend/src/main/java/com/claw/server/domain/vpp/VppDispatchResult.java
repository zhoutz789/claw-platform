package com.claw.server.domain.vpp;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 虚拟电厂调度执行结果（对应 claw.vpp_dispatch_results，V113 表）。
 *
 * <p>按指令采样实际功率与偏差，{@code complianceRate = 1 - |偏差| / |目标|}；
 * 目标为 0 时不做除法（避免除零），合规率置 null。
 */
@Entity
@Table(name = "vpp_dispatch_results", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VppDispatchResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    private Instant sampleAt;

    @Column(name = "actual_w")
    private BigDecimal actualW;

    @Column(name = "deviation_w")
    private BigDecimal deviationW;

    private BigDecimal complianceRate;

    @Column(length = 255)
    private String remark;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
