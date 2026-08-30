package com.claw.server.domain.production;

import jakarta.persistence.*;
import lombok.*;
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
