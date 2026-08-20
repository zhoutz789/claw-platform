package com.claw.server.domain.role;

import com.claw.server.common.enums.RoleSource;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 人人经济动态角色包（对应 claw.user_role_packages）。
 * 一个用户 = N 个并行角色权限包，随行为自动授予/回收（三层权限的「角色包层」）。
 */
@Entity
@Table(name = "user_role_packages", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRolePackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long roleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RoleSource source = RoleSource.APPLY;

    @Column(nullable = false)
    @Builder.Default
    private Instant grantedAt = Instant.now();

    private Instant revokedAt;

    public boolean isActive() {
        return revokedAt == null;
    }
}
