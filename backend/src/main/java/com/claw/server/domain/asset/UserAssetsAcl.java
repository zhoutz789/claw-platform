package com.claw.server.domain.asset;

import com.claw.server.common.enums.AclRelation;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 资产级 ACL（对应 claw.user_assets_acl）。
 * 三层权限的「资产 ACL 层」：一个用户对某资产的管理/使用/承租关系，即时生效、流转同步失效。
 */
@Entity
@Table(name = "user_assets_acl", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAssetsAcl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long assetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AclRelation relation;

    private Long grantedBy;
    private Instant expiredAt;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
