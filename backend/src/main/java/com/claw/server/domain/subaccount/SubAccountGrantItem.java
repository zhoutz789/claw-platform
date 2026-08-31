package com.claw.server.domain.subaccount;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;

/**
 * 子账号授权明细（对应 claw.sub_account_grant_items，V61，仅 PARTIAL 模式使用）。
 *
 * <p>复合主键 {@code (grant_id, permission_code)}，引用既有 {@code permissions.code}
 * （含 MENU 类型，天然是树，前端菜单树直接复用）。
 *
 * <p>⚠️ 复合主键内部类必须命名为 {@code Key}（命名 {@code Id} 会遮蔽
 * {@code jakarta.persistence.Id}，导致 @Id 解析失败）。
 */
@Entity
@Table(name = "sub_account_grant_items", schema = "claw")
@IdClass(SubAccountGrantItem.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubAccountGrantItem implements Serializable {

    @Id
    @Column(name = "grant_id", nullable = false)
    private Long grantId;

    @Id
    @Column(name = "permission_code", nullable = false, length = 80)
    private String permissionCode;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** 复合主键（grant_id + permission_code）。类名不能叫 Id，否则会遮蔽 jakarta.persistence.Id。
     *
     * <p>必须实现 equals/hashCode：@IdClass 复合主键不实现会导致 Hibernate 二级缓存错乱、
     * Set/Map 集合操作异常、实体脏检查失效（启动日志 HHH000038/HHH000039）。
     */
    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long grantId;
        private String permissionCode;
    }
}
