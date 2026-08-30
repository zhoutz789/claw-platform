package com.claw.server.domain.subaccount;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 子账号（对应 claw.sub_accounts，V61）。
 *
 * <p>主账号（服务站管理者 / 厂家管理员 / 商家管理员）为协作成员开设的登录账号。
 * Q11 默认：<b>子账号不可再开子账号</b> —— owner 只允许是「主账号主体」，不递归。
 *
 * <p>数据范围继承主账号主体（O31）：子账号登录后由 {@code PrincipalResolver}
 * 经本表回溯到 {@code ownerPrincipalType} / {@code ownerPrincipalId}。
 */
@Entity
@Table(name = "sub_accounts", schema = "claw",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"owner_principal_type", "owner_principal_id", "user_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 主账号主体类型：STATION / MANUFACTURER / MERCHANT。 */
    @Column(name = "owner_principal_type", nullable = false, length = 20)
    private String ownerPrincipalType;

    /** 主账号主体 ID（station_id / manufacturer_id / merchant_id）。 */
    @Column(name = "owner_principal_id", nullable = false)
    private Long ownerPrincipalId;

    /** 子账号登录用户。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "display_name", length = 80)
    private String displayName;

    @Column(length = 40)
    private String phone;

    /** ACTIVE / DISABLED。 */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /** 是否启用中。 */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
