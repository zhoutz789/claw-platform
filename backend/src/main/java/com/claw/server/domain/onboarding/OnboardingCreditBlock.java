package com.claw.server.domain.onboarding;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 授信额度超限阻断记录（对应 claw.onboarding_credit_blocks，V60，本设计新增表）。
 *
 * <p>额度超限是<b>风控事件</b>，需要可追溯：谁、何时、想发多少、当时占用多少、超了多少。
 * 只抛异常不落库会导致超限纠纷无据可查。
 */
@Entity
@Table(name = "onboarding_credit_blocks", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingCreditBlock {

    /** 阻断场景。 */
    public enum Scene {
        /** 厂家铺货 / 发货入站（硬阻断 C1）。 */
        CONSIGN_SHIP,
        /** 调拨入站（目标站收货，硬阻断 C2）。 */
        TRANSFER_IN,
        /** 履约发货入站（硬阻断 C3）。 */
        FULFILL_SHIP,
        /** 建调拨单（软预检 C4，仅记录不阻断）。 */
        TRANSFER_CREATE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CRB{yyyyMMdd}{4位序号}。 */
    @Column(name = "block_no", nullable = false, length = 40)
    private String blockNo;

    @Column(name = "principal_type", nullable = false, length = 20)
    private String principalType;

    @Column(name = "principal_id", nullable = false)
    private Long principalId;

    @Column(nullable = false, length = 32)
    private String scene;

    /** TRANSFER / FULFILLMENT / CONSIGNMENT。 */
    @Column(name = "biz_ref_type", length = 32)
    private String bizRefType;

    @Column(name = "biz_ref_id")
    private Long bizRefId;

    /** 本次尝试入站设备数。 */
    @Column(name = "device_count", nullable = false)
    private Integer deviceCount;

    /** 本次尝试新增货值。 */
    @Column(name = "incoming_value", nullable = false, precision = 16, scale = 2)
    private BigDecimal incomingValue;

    /** 当时已占用货值。 */
    @Column(name = "used_value", nullable = false, precision = 16, scale = 2)
    private BigDecimal usedValue;

    /** 当时额度上限。 */
    @Column(name = "credit_limit", nullable = false, precision = 16, scale = 2)
    private BigDecimal creditLimit;

    /** 超出金额。 */
    @Column(name = "overflow_value", nullable = false, precision = 16, scale = 2)
    private BigDecimal overflowValue;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
