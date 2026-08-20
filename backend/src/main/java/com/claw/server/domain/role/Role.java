package com.claw.server.domain.role;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 角色目录（对应 claw.roles）。
 * 既是 RBAC 平台固定角色（PLATFORM_ADMIN 等），也是人人经济动态权限包（CONSUMER 等）的目录来源。
 * grants / grant_rule 以 JSON 文本存储（PostgreSQL JSONB），业务层按需解析。
 */
@Entity
@Table(name = "roles", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "name_i18n", nullable = false)
    private String nameI18n;

    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private String grants = "{}";

    @Builder.Default
    private Boolean autoGrant = false;

    @Column(columnDefinition = "jsonb")
    private String grantRule;

    @Builder.Default
    private String status = "ACTIVE";

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
