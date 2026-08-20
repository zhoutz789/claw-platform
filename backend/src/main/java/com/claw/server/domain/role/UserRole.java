package com.claw.server.domain.role;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * RBAC 平台角色分配（对应 claw.user_roles）。
 * 平台级固定角色（管理员/站方/厂家），由平台/管理员授予，构成三层权限的「RBAC 层」。
 */
@Entity
@Table(name = "user_roles", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long roleId;

    private Long grantedBy;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
