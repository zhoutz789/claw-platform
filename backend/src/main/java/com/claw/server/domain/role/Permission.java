package com.claw.server.domain.role;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 权限点目录（菜单/按钮）。对应 claw.permissions。 */
@Entity
@Table(name = "permissions", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    /** MENU / BUTTON */
    @Column(nullable = false)
    @Builder.Default
    private String ptype = "MENU";

    private String parentCode;
    private String path;
    @Builder.Default
    private Integer sortNo = 0;
    private String icon;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
