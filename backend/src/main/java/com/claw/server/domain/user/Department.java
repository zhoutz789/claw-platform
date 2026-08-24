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

    @Builder.Default
    private Long tenantId = 1L;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
