package com.claw.server.domain.role;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** 角色模板（业务视角权限打包，引用既有 permissions 原子码子集）。对应 claw.role_templates。 */
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

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    /** 与 code 同义，便于 @DataScope 解析（MANUFACTURER / STATION / CUSTOMER / PLATFORM_ADMIN）。 */
    @Column(name = "principal_type", nullable = false)
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
