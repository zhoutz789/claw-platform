package com.claw.server.domain.role;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;

/** 角色模板 ↔ 权限码（模板展开为账号权限集合的来源）。对应 claw.role_template_permissions（复合主键）。 */
@Entity
@Table(name = "role_template_permissions", schema = "claw")
@IdClass(RoleTemplatePermission.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleTemplatePermission implements Serializable {

    @Id
    @Column(name = "template_code", nullable = false)
    private String templateCode;

    @Id
    @Column(name = "permission_code", nullable = false)
    private String permissionCode;

    /** 复合主键（template_code + permission_code）。类名不能叫 Id，否则会遮蔽 jakarta.persistence.Id 导致 @Id 解析失败。 */
    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String templateCode;
        private String permissionCode;
    }
}
