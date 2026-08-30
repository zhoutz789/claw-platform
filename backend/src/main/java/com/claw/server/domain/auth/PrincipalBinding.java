package com.claw.server.domain.auth;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 主体绑定（对应 V47 claw.principal_bindings）。账号 ↔ 厂家/服务站 1:1（Q5 严格 1:1）。
 * 是 B 域数据范围解析入口：登录用户 → manufacturer_id / station_id。
 */
@Entity
@Table(name = "principal_bindings", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrincipalBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** MANUFACTURER / STATION。 */
    @Column(nullable = false, length = 20)
    private String principalType;

    /** manufacturer_id 或 station_id。 */
    @Column(nullable = false)
    private Long principalId;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
