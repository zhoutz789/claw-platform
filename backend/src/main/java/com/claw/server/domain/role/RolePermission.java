package com.claw.server.domain.role;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 角色-权限矩阵（增删改查+导出+按钮级）。对应 claw.role_permissions。 */
@Entity
@Table(name = "role_permissions", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long roleId;

    @Column(nullable = false)
    private String permissionCode;

    @Builder.Default
    private Boolean canRead = false;
    @Builder.Default
    private Boolean canCreate = false;
    @Builder.Default
    private Boolean canUpdate = false;
    @Builder.Default
    private Boolean canDelete = false;
    @Builder.Default
    private Boolean canExport = false;

    @Column(columnDefinition = "text")
    @Builder.Default
    private String buttonsJson = "{}";

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
