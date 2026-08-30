package com.claw.server.domain.auth;

import jakarta.persistence.*;
import lombok.*;

/**
 * 角色组 ↔ 角色模板（对应 V47 claw.role_group_templates）。
 */
@Entity
@Table(name = "role_group_templates", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleGroupTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String groupCode;

    @Column(nullable = false, length = 40)
    private String templateCode;
}
