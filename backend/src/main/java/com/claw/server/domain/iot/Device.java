package com.claw.server.domain.iot;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 设备（对应 claw.devices，V8 表）。
 * 车机/电池 BMS/充电桩/换电柜/广告屏/摄像头统一抽象，device_type 区分。
 */
@Entity
@Table(name = "devices", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    /** VEHICLE_TCU | BATTERY_BMS | CHARGER | CABINET | AD_SCREEN | CAMERA。 */
    @Column(nullable = false, length = 16)
    private String deviceType;

    @Column(unique = true)
    private String imei;

    private String protocolVer;

    private Instant lastOnlineAt;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
