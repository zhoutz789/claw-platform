package com.claw.server.domain.fulfillment;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * 待履约订单明细（对应 V50 claw.fulfillment_order_items）。
 * 发货/取货时绑定具体设备（device_id），记录单价与数量。
 */
@Entity
@Table(name = "fulfillment_order_items", schema = "claw",
        uniqueConstraints = @UniqueConstraint(columnNames = {"fulfillment_order_id", "product_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FulfillmentOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fulfillment_order_id", nullable = false)
    private Long fulfillmentOrderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "asset_id")
    private Long assetId;

    /** 发货/取货绑定的具体设备（本域业务代码以 device 为粒度）。 */
    @Column(name = "device_id")
    private Long deviceId;

    @Column(nullable = false)
    @Builder.Default
    private Integer qty = 1;

    @Column(precision = 12, scale = 2)
    private BigDecimal price;
}
