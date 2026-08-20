package com.claw.server.domain.asset;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 资产状态变更历史（对应 claw.asset_status_logs）。
 * 所有状态机流转强制留痕（技术文档 2.1 设计原则 3）。
 */
@Entity
@Table(name = "asset_status_logs", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetStatusLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    private String fromStatus;
    private String toStatus;

    private Long operatorId;
    private String reason;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
