package com.claw.server.domain.compliance;

import com.claw.server.common.enums.PermitStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 无人机空域许可（对应 claw.drone_airspace_permits，V143 新增）。
 *
 * <p>「按资产 + 省域 + 时间窗」的一等合规凭证：SSCA（柬埔寨民航局）签发，
 * {@code PermitGate} 在 KH-EARLY-OPERATION 档位下要求受控指令（REMOTE_START 等）
 * 必须持有一张 {@code status=ACTIVE} 且 {@code valid_from <= now <= valid_to} 的许可。
 *
 * <p>{@code scopeProvince} 为许可覆盖的作业省域（可空 = 全省域不限）；
 * 省域不匹配的许可对其他省无效。
 */
@Entity
@Table(name = "drone_airspace_permits", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DroneAirspacePermit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 无人机 asset_id。 */
    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    /** 许可证号（唯一，SSCA 文书编号）。 */
    @Column(name = "permit_no", nullable = false, unique = true, length = 64)
    private String permitNo;

    /** 签发机构（默认 SSCA）。 */
    @Column(name = "issuer", nullable = false, length = 64)
    @Builder.Default
    private String issuer = "SSCA";

    /** 覆盖省域（可空 = 不限省域）。 */
    @Column(name = "scope_province", length = 64)
    private String scopeProvince;

    /** 生效起点（含）。 */
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /** 生效终点（含）。 */
    @Column(name = "valid_to", nullable = false)
    private Instant validTo;

    /** 许可状态（只认 ACTIVE）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private PermitStatus status = PermitStatus.ACTIVE;

    /** 文书引用（扫描件/回执号）。 */
    @Column(name = "doc_ref", length = 128)
    private String docRef;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private Long tenantId = 1L;

    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
