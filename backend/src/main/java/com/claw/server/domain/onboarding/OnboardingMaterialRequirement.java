package com.claw.server.domain.onboarding;

import jakarta.persistence.*;
import lombok.*;

/**
 * 入驻材料清单配置（对应 claw.onboarding_material_requirements，V59）。
 *
 * <p>按 {@code applicant_type} 配置所需材料项、是否必填、数量上下限、排序、说明文案（O7/B1）。
 * 前端据此<b>动态渲染</b>申请表单：服务站要土地/场地照，厂家要生产资质/品牌授权，
 * 商家要经营品类且不需要土地证明（Q9 独立主体推论）。
 */
@Entity
@Table(name = "onboarding_material_requirements", schema = "claw",
        uniqueConstraints = @UniqueConstraint(columnNames = {"applicant_type", "material_code"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingMaterialRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** STATION / MANUFACTURER / MERCHANT。 */
    @Column(name = "applicant_type", nullable = false, length = 20)
    private String applicantType;

    @Column(name = "material_code", nullable = false, length = 40)
    private String materialCode;

    @Column(name = "material_name", nullable = false, length = 80)
    private String materialName;

    /** TEXT / TEXTAREA / IMAGE / IMAGES / FILE / LOCATION。 */
    @Column(name = "input_type", nullable = false, length = 16)
    @Builder.Default
    private String inputType = "TEXT";

    /** 注意用包装类型 Boolean：primitive boolean 的 Lombok getter 是 isRequired()，业务需 getRequired()。 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean required = Boolean.TRUE;

    @Column(name = "min_count")
    private Integer minCount;

    @Column(name = "max_count")
    private Integer maxCount;

    @Column(columnDefinition = "text")
    private String hint;

    @Column(name = "sort_no", nullable = false)
    @Builder.Default
    private Integer sortNo = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = Boolean.TRUE;
}
