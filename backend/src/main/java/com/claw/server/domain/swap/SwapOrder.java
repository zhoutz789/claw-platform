package com.claw.server.domain.swap;

import com.claw.server.common.enums.SwapStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 换电订单（对应 claw.swap_orders）。
 * 状态机见 {@link SwapStatus}；每次状态流转写入 {@link OrderEvent}。
 */
@Entity
@Table(name = "swap_orders", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SwapOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNo;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long stationId;

    /** 车辆（可选，协议匹配）。 */
    private Long vehicleId;

    /** 满电电池（出）。 */
    private Long batteryOutId;

    /** 欠电电池（收，可为空=首次换电）。 */
    private Long batteryInId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private SwapStatus status = SwapStatus.CREATED;

    private String protocolVer;

    /** 新电池押金（create 时冻结，动态残值）。 */
    @Column(nullable = false)
    @Builder.Default
    private BigDecimal batteryDeposit = BigDecimal.ZERO;

    /** 旧电池押金（confirm 时退还用户）。 */
    @Column(nullable = false)
    @Builder.Default
    private BigDecimal oldBatteryDeposit = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal estKwh = new BigDecimal("2.00");

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal estElecFee = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal estServiceFee = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal estTotal = BigDecimal.ZERO;

    private BigDecimal actualKwh;
    private BigDecimal actualElecFee;
    private BigDecimal actualServiceFee;
    private BigDecimal actualTotal;

    private BigDecimal socStart;
    private BigDecimal socEnd;

    /** 锁定快照：{elecRate, serviceRate, serviceSplit}。 */
    @JdbcTypeCode(SqlTypes.JSON)
    private String priceSnapshot;

    private String settleStatus;
    private String cancelReason;

    @Builder.Default
    private Long tenantId = 1L;

    @Builder.Default
    private Boolean deleted = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
