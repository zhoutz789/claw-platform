package com.claw.server.domain.manufacturer;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 产品模板字段（EAV 单表，对应 claw.product_template_fields）。
 *
 * <p>否决"每产品模版单独建表"：字段 schema 走本表，(product_id, field_key) 唯一；
 * 产品实例的扩展属性值存 {@code Product.attrJson} / {@code Product.paramsJson}，不另建表。
 * type ∈ {number, text, select, date, boolean}。
 */
@Entity
@Table(name = "product_template_fields", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductTemplateField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "field_key", nullable = false, length = 64)
    private String fieldKey;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    /** number / text / select / date / boolean */
    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "unit", length = 16)
    private String unit;

    /** select 选项（JSON 数组）。 */
    @Column(name = "options_json", columnDefinition = "text")
    private String optionsJson;

    @Column(name = "required", nullable = false)
    @Builder.Default
    private boolean required = false;

    @Column(name = "sort_no")
    @Builder.Default
    private int sortNo = 0;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private Long tenantId = 1L;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
