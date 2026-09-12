package com.claw.server.domain.ocpp;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * OCPP 充电站点（对应 claw.ocpp_charging_stations，V118 表）。
 *
 * <p>一个充电桩 = 一个 chargePointId（也作为 WS 路径与 devices.device_no）。
 * 站点在线/离线由心跳驱动；鉴权令牌在 BootNotification 阶段校验。
 */
@Entity
@Table(name = "ocpp_charging_stations", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChargingStation {

    /** OCPP chargePointId（= /ocpp/{chargePointId} WS 路径 = devices.device_no）。 */
    @Id
    @Column(name = "charge_point_id", nullable = false, length = 64)
    private String chargePointId;

    /** 关联 assets.id（AssetType=CHARGER）。 */
    private Long assetId;

    private String vendor;
    private String model;
    private String firmware;

    /** ONLINE / OFFLINE / FAULT。 */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "OFFLINE";

    private Instant lastHeartbeat;

    /** BootNotification 鉴权令牌（为空表示免校验，仅用于内网/隧道后可信环境）。 */
    @Column(name = "auth_token", length = 128)
    private String authToken;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
