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

    /**
     * VEHICLE_TCU | BATTERY_BMS | CHARGER | CABINET | AD_SCREEN | CAMERA | DRONE_FCU
     * | PV_GATEWAY | INVERTER | PV_METER | WEATHER_STATION。
     *
     * <p>列宽 VARCHAR(16)：WEATHER_STATION 为最长取值（15 字符），新增类型前须先核对长度。
     */
    @Column(nullable = false, length = 16)
    private String deviceType;

    @Column(unique = true)
    private String imei;

    /** 设备唯一编号（= 选型书 DeviceID / 二维码内容），车辆终端对接契约身份。 */
    @Column(unique = true)
    private String deviceNo;

    /** 下行指令 HMAC-SHA256 签名密钥（出厂烧录 / 平台注册时生成）。 */
    @Column(length = 128)
    private String secret;

    /** 继电器/开关当前状态（0 断 / 1 通）。 */
    @Builder.Default
    private Integer relayState = 0;

    /** 二维码载体（可为 device_no 或带平台地址的 JSON）。 */
    @Column(columnDefinition = "text")
    private String qrPayload;

    private String protocolVer;

    private Instant lastOnlineAt;

    /** 设备生命周期状态（R4 冗余字段，权威历史见 lifecycle_events）。 */
    @Column(name = "lifecycle_status", length = 20)
    private String lifecycleStatus;

    /** 实例化来源商品（生产入库 R3）。 */
    @Column(name = "product_id")
    private Long productId;

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
