package com.claw.server.domain.auth;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 角色模板（对应 V47 claw.role_templates）。业务视角打包既有权限码（Q4 默认）。
 * 现有 roles 表是权限真源；本表为管理端「模板」视图，授予时回写 roles.grants（见 RoleTemplateService）。
 */
@Entity
@Table(name = "role_templates", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    /** 与 code 同义，便于 @DataScope 解析：MANUFACTURER / STATION / CUSTOMER / PLATFORM_ADMIN。 */
    @Column(nullable = false, length = 20)
    private String principalType;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
