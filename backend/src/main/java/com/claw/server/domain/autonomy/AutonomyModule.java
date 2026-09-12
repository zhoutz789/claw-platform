package com.claw.server.domain.autonomy;

import com.claw.server.common.enums.DriveMode;
import com.claw.server.common.enums.SafetyState;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 无人车自主模块（与 VEHICLE 资产一对一，复用 AssetType.VEHICLE，不升格资产类）。
 * 对应 claw.autonomy_modules（V121）。
 */
@Entity
@Table(name = "autonomy_modules", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutonomyModule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "algo_version", nullable = false)
    private String algoVersion;

    @Column(name = "firmware_version")
    private String firmwareVersion;

    @Column(name = "learning_model_id")
    private Long learningModelId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "drive_mode", nullable = false)
    private DriveMode driveMode = DriveMode.ASSISTED;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "safety_state", nullable = false)
    private SafetyState safetyState = SafetyState.NORMAL;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
