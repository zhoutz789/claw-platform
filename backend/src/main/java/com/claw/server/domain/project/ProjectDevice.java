package com.claw.server.domain.project;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 项目-设备绑定（对应 claw.project_devices）。
 *
 * <p>{@code product_id} 在绑定时自动从 {@code assets.product_id} 带出（不冗余维护），
 * {@code UNIQUE(project_id, asset_id)} 保证同一设备在同一项目内不重复绑定。
 */
@Entity
@Table(name = "project_devices", schema = "claw",
        uniqueConstraints = @UniqueConstraint(name = "uk_project_device",
                columnNames = {"project_id", "asset_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long assetId;

    private Long productId;

    @Column(length = 16)
    private String category;

    @Builder.Default
    private int sortNo = 0;

    @Column(nullable = false)
    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
