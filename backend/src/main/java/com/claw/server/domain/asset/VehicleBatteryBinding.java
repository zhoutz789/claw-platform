package com.claw.server.domain.asset;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 车辆 ↔ 电池 绑定记录（对应 claw.vehicle_battery_bindings）。
 *
 * <p>一条记录代表某车辆在某时间段内绑定某电池；{@code unboundAt} 为空表示当前生效绑定。
 * 历史每次换电/充电时的电池归属以本表为准（三视图基础）。
 */
@Entity
@Table(name = "vehicle_battery_bindings", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleBatteryBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 车辆 asset_id（FK → vehicles.asset_id）。 */
    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    /** 电池 asset_id（FK → batteries.asset_id）。 */
    @Column(name = "battery_id", nullable = false)
    private Long batteryId;

    @Column(name = "bound_at", nullable = false)
    private Instant boundAt;

    /** 解绑时间；为空表示当前绑定。 */
    @Column(name = "unbound_at")
    private Instant unboundAt;

    /** 协议版本（换电/充电协议匹配，软校验用）。 */
    @Column(name = "protocol_ver", length = 32)
    private String protocolVer;
}
