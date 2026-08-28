package com.claw.server.domain.order;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 订单逐台登记实体（对应 claw.customer_order_unit_registrations）。
 *
 * <p>1 个订单项 → N 个登记行 → N 台资产；登记即生成 1 台资产（asset_id 不为空）。
 * 逐台二维码 / 车架号 / 电机号 / 主部件编号在此台账落地（监管可直查），
 * 并同步拷入 assets + vehicles/drones（按资产类型）。
 *
 * <p>状态 {@link UnitRegistrationStatus}：默认 REGISTERED；CONFIRMED 为站方复核后；
 * 发货守卫按 REGISTERED 计数（设计 §2.3）。
 */
@Entity
@Table(name = "customer_order_unit_registrations", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnitRegistration {

    /** 登记状态：DRAFT（草稿）/ REGISTERED（已登记，可发货）/ CONFIRMED（复核后）。 */
    public enum UnitRegistrationStatus {
        DRAFT,
        REGISTERED,
        CONFIRMED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private Integer seq;                 // 该 SKU 内第几台（1..quantity）

    @Column(name = "qr_code", nullable = false, unique = true)
    private String qrCode;               // 逐台唯一二维码

    private String vin;                  // 车架号（车辆）
    private String frameNo;              // 车架号(主部件)
    private String motorNo;              // 电机号

    @Column(name = "component_nos_json")
    private String componentNosJson;     // 主要元件编号集合（JSON）

    @Column(name = "asset_id", nullable = false, unique = true)
    private Long assetId;                // 登记即生成 1 台资产

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private UnitRegistrationStatus status = UnitRegistrationStatus.REGISTERED;

    @Column(nullable = false)
    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
