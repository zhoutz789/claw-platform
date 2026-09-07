package com.claw.server.domain.certificate;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 合格证模板字段（EAV 单表，全局单一合格证类型，对应 claw.certificate_template_fields）。
 *
 * <p>复用产品模板字段（product_template_fields, V35）范式，区别在于合格证模板是<b>全局</b>的
 * （不绑定 product_id），故以 {@code field_key} UNIQUE 约束。type ∈ {number,text,select,date,boolean}。
 */
@Entity
@Table(name = "certificate_template_fields", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CertificateTemplateField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "field_key", nullable = false, unique = true, length = 64)
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
