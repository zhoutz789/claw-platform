package com.claw.server.domain.role;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;

/** 角色组 ↔ 模板。对应 claw.role_group_templates（复合主键）。 */
@Entity
@Table(name = "role_group_templates", schema = "claw")
@IdClass(RoleGroupTemplate.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleGroupTemplate implements Serializable {

    @Id
    @Column(name = "group_code", nullable = false)
    private String groupCode;

    @Id
    @Column(name = "template_code", nullable = false)
    private String templateCode;

    /** 复合主键（group_code + template_code）。类名不能叫 Id，否则会遮蔽 jakarta.persistence.Id 导致 @Id 解析失败。 */
    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String groupCode;
        private String templateCode;
    }
}
