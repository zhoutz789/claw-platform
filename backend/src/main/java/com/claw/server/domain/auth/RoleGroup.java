package com.claw.server.domain.auth;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 角色组（对应 V47 claw.role_groups，系统管理第 6 项能力载体）。批量治理权限的容器。
 */
@Entity
@Table(name = "role_groups", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    private Long createdBy;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
