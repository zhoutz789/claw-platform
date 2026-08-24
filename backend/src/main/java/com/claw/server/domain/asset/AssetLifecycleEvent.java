package com.claw.server.domain.asset;

import com.claw.server.common.enums.AssetLifecycleStage;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 资产生命周期事件（生产/流通/使用/维修/回收/销毁）。对应 claw.asset_lifecycle_events。 */
@Entity
@Table(name = "asset_lifecycle_events", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetLifecycleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetLifecycleStage stage;

    private String location;
    private Long operatorId;
    private String note;

    @Column(nullable = false)
    @Builder.Default
    private Instant occurredAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
