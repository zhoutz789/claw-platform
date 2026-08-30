package com.claw.server.domain.subaccount;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 子账号授权（对应 claw.sub_account_grants，V61）。
 *
 * <p><b>grant_mode=ALL 的自动继承机制</b>（周老板「功能会不断增加」诉求的核心）：
 * ALL 模式<b>不存具体权限码</b>，只存 {@code templateCode}；运行时展开
 * {@code role_template_permissions(template_code)}。平台新增功能时只需往 permissions 注册新码
 * 并挂到模板，<b>已授权 ALL 的子账号零改动自动获得</b>。
 *
 * <p>PARTIAL 模式落明细到 {@link SubAccountGrantItem}，实际生效集合 = 明细 ∩ 主账号模板集合（O30）。
 */
@Entity
@Table(name = "sub_account_grants", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubAccountGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sub_account_id", nullable = false, unique = true)
    private Long subAccountId;

    /** ALL / PARTIAL。 */
    @Column(name = "grant_mode", nullable = false, length = 16)
    private String grantMode;

    /** ALL 模式跟随的角色模板（MANUFACTURER / STATION / MERCHANT）。 */
    @Column(name = "template_code", length = 40)
    private String templateCode;

    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "granted_at", nullable = false)
    @Builder.Default
    private Instant grantedAt = Instant.now();

    /** ACTIVE / REVOKED。 */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    /** 是否生效中。 */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    /** 是否「全部功能」模式。 */
    public boolean isAll() {
        return "ALL".equals(grantMode);
    }
}
