package com.claw.server.domain.category;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 通用多级商品分类（对应 claw.categories）。
 *
 * <p>类似淘宝的一级/二级/三级/四级分类树；普通商品与厂家运营商品共用此分类，
 * 发布商品时作为商品类别选择。自引用 parent_id 表达层级，层级由后端在内存组装；
 * 软删除（deleted 标记），不物理删行，便于资产/商品引用不被破坏。
 */
@Entity
@Table(name = "categories", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    /** 父分类 id（树根 = NULL）；无 DB 外键，层级由 Controller 在内存组装。 */
    @Column(name = "parent_id")
    @Builder.Default
    private Long parentId = null;

    /** 层级：根 = 0，逐级 +1。 */
    @Column(name = "level")
    @Builder.Default
    private int level = 0;

    /** 同级排序。 */
    @Column(name = "sort_no")
    @Builder.Default
    private int sortNo = 0;

    /** 可选邮编式层级码（如 'A01' / 'A01B02'）。 */
    @Column(name = "code", length = 64)
    @Builder.Default
    private String code = "";

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private Long tenantId = 1L;

    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
