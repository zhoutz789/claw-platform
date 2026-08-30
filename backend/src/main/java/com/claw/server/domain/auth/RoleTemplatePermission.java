package com.claw.server.domain.auth;

import jakarta.persistence.*;
import lombok.*;

/**
 * 角色模板 ↔ 权限码（对应 V47 claw.role_template_permissions）。
 */
@Entity
@Table(name = "role_template_permissions", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleTemplatePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String templateCode;

    @Column(nullable = false, length = 80)
    private String permissionCode;
}
