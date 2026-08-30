package com.claw.server.domain.role;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 主体绑定（账号 ↔ 业务主体，1:1，Q5 默认严格 1:1）。
 * principal_type ∈ {MANUFACTURER, STATION}，principal_id = manufacturer_id 或 station_id。
 * 是增量 B 数据范围解析的入口（账号 → 厂家/服务站主体）。对应 claw.principal_bindings。
 */
@Entity
@Table(name = "principal_bindings", schema = "claw",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "principal_type"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrincipalBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "principal_type", nullable = false)
    private String principalType;

    @Column(name = "principal_id", nullable = false)
    private Long principalId;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
