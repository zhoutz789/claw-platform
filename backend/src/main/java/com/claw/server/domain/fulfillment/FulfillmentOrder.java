package com.claw.server.domain.fulfillment;

import com.claw.server.common.enums.FulfillmentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 待履约订单（对应 V50 claw.fulfillment_orders，R6）。
 * 用户付款即冻结（frozen_amount）；取货扫码履约（PICKUP）才进入结算（R7）。
 * 状态机：PENDING_PAYMENT→PAID_FROZEN→CONFIRMED→SHIPPED→RECEIVED→PICKED_UP→SETTLED；
 *        任意节点可 CANCELLED / EXPIRED（释放冻结，Q6 不自动全额退款）。
 */
@Entity
@Table(name = "fulfillment_orders", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FulfillmentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true)
    private String orderNo;

    @Column(name = "customer_user_id", nullable = false)
    private Long customerUserId;

    @Column(name = "manufacturer_id", nullable = false)
    private Long manufacturerId;

    @Column(name = "station_id", nullable = false)
    private Long stationId;

    /** 缺货远程代下单（B9）：TRUE=用户不在服务站现场，由厂家远程代下单发货。 */
    @Column(name = "remote_order", nullable = false)
    @Builder.Default
    private boolean remoteOrder = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FulfillmentStatus status = FulfillmentStatus.PENDING_PAYMENT;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "frozen_amount", precision = 12, scale = 2)
    private BigDecimal frozenAmount;

    @Column(name = "payment_ref", length = 80)
    private String paymentRef;

    /** 履约超时时点（Q1 订单分支：从下单起算，默认 90 天，可配）。 */
    @Column(name = "expire_at")
    private Instant expireAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /**
     * 订单明细（非持久化，仅详情接口填充）。
     *
     * <p>列表接口不填充以避免 N+1；{@code @JsonInclude(NON_NULL)} 保证列表响应里不出现 {@code "items": null}。
     * 取货扫码页据此直接挑选待取设备，无需手填 deviceIds。
     */
    @Transient
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<FulfillmentOrderItem> items;
}
