package com.claw.server.domain.project;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 设备授权记录（对应 claw.device_authorizations）。
 *
 * <p>三态正交（分别落不同表/不同记录，可同时并存）：
 * <ul>
 *   <li>TRANSFER —— 所有权→资产大厅（公开可见，资产状态置 LISTED）；</li>
 *   <li>SHARE —— 入共享池（委托 SharedPoolService.poolAsset）；</li>
 *   <li>AUTHORIZE —— 仅授予使用权（不转移所有权），scope_json 存权限集合。</li>
 * </ul>
 * {@code auth_type}/{@code status} 存为 VARCHAR(16)，取值见 {@link DeviceAuthType} 与服务层校验。
 */
@Entity
@Table(name = "device_authorizations", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceAuthorization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    @Column(nullable = false)
    private Long grantorUserId;

    private Long granteeUserId;

    @Column(nullable = false, length = 16)
    private String authType;

    @Column(columnDefinition = "TEXT")
    private String scopeJson;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
