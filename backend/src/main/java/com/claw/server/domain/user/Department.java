package com.claw.server.domain.user;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 部门（对应 claw.departments）。
 * 数据范围 DEPARTMENT/TYPE enforcement 的维度锚点：用户归部门，资产按管理人/使用人所属部门可见。
 */
@Entity
@Table(name = "departments", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** 父部门 id（部门层级）：树根 parent_id = NULL；无 DB 外键，层级由 Controller 在内存组装。 */
    @Column(name = "parent_id")
    @Builder.Default
    private Long parentId = null;

    /** 邮编式层级码（如 'A01' / 'A01B02'），由父部门 org_code + 本级序号派生；树根为空串。 */
    @Column(name = "org_code", length = 32)
    @Builder.Default
    private String orgCode = "";

    @Builder.Default
    private Long tenantId = 1L;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
