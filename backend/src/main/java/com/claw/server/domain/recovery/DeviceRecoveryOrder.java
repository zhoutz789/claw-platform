package com.claw.server.domain.recovery;

import com.claw.server.common.enums.RecoveryStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 设备回收单（对应 V51 claw.device_recovery_orders，R8）。
 * 既有 recovery_orders 表为车辆残值回收，不混用，故新表。
 * 触发：UNSOLD_TIMEOUT（寄售未成交超时）/ FULFILL_TIMEOUT（待履约超时）/ MANUAL。
 * status：PENDING→CONFIRMED→MARKED→RETURNED；CANCELLED。
 */
@Entity
@Table(name = "device_recovery_orders", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceRecoveryOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recovery_no", nullable = false, unique = true)
    private String recoveryNo;

    @Column(name = "manufacturer_id", nullable = false)
    private Long manufacturerId;

    @Column(name = "station_id", nullable = false)
    private Long stationId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    /** UNSOLD_TIMEOUT / FULFILL_TIMEOUT / MANUAL。 */
    @Column(length = 40)
    private String reason;

    /** AUTO（系统扫描触发）/ MANUAL（人工）。 */
    @Column(name = "trigger_type", nullable = false, length = 20)
    @Builder.Default
    private String triggerType = "MANUAL";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RecoveryStatus status = RecoveryStatus.PENDING;

    /** 回流厂家自有库时点。 */
    @Column(name = "inbound_at")
    private Instant inboundAt;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "confirmed_at")
    private Instant confirmedAt;
}
