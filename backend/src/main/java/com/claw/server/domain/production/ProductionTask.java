package com.claw.server.domain.production;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** 生产任务（基于 product 实例化 device 的批次，R3/B1）。对应 claw.production_tasks。 */
@Entity
@Table(name = "production_tasks", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manufacturer_id", nullable = false)
    private Long manufacturerId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "plan_quantity", nullable = false)
    @Builder.Default
    private Integer planQuantity = 0;

    @Column(name = "produced_quantity", nullable = false)
    @Builder.Default
    private Integer producedQuantity = 0;

    /** CREATED / PRODUCING / DONE。 */
    @Column(nullable = false)
    @Builder.Default
    private String status = "CREATED";

    /**
     * 批次规格快照。列类型为 JSONB，必须以 {@link SqlTypes#JSON} 绑定：
     * 否则 Hibernate 按 varchar 绑定（null 亦按 varchar 类型发参），PostgreSQL 直接报
     * 「column "spec_json" is of type jsonb but expression is of type character varying」。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec_json", columnDefinition = "jsonb")
    private String specJson;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
