package com.claw.server.domain.category;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 品类级字段模板（对应 claw.category_field_templates）。
 *
 * <p>与 {@link com.claw.server.domain.manufacturer.ProductTemplateField} 同构，
 * 唯一区别是归属维度由「单个商品 product_id」上提为「一个品类 category_id」：
 * 一个品类（如充电桩 - 直流快充 E0302）只需维护一份专业参数字段定义，
 * 商品创建时由 {@code ProductTemplateFieldService#copyFromCategory} 复制一份到
 * {@code product_template_fields}，避免每个商品手工重填。
 *
 * <p>type ∈ {number, text, select, date, boolean}；(category_id, field_key) 唯一。
 */
@Entity
@Table(name = "category_field_templates", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryFieldTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属品类 id（claw.categories.id，非 DB 外键，分类软删除不物理删）。 */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

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
