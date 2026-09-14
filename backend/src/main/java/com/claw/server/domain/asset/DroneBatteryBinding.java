package com.claw.server.domain.asset;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 无人机 ↔ 电池 绑定记录（对应 claw.drone_battery_binding，V140）。
 *
 * <p>镜像 {@link VehicleBatteryBinding}：一条记录代表某无人机在某时间段内使用某电池；
 * {@code active = true} 且 {@code unboundAt} 为空表示当前生效绑定。
 * 同一无人机任一时刻至多一条生效绑定（由 {@link DroneBatteryService#bind} 保证）。
 *
 * <p>{@code cycles} 记该次绑定期间的电池循环次数，供热插拔换电 / 机场充电计量。
 * 外键目标为 {@code drones(asset_id)} 与 {@code batteries(asset_id)}（二者主键为 asset_id，非 id）。
 * 字段名以大写字母结尾者均显式 {@code @Column}（ArchUnit 规则），其余列亦全部显式声明以对齐 DDL。
 */
@Entity
@Table(name = "drone_battery_binding", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DroneBatteryBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 无人机 asset_id（FK → drones.asset_id）。 */
    @Column(name = "drone_asset_id", nullable = false)
    private Long droneAssetId;

    /** 电池 asset_id（FK → batteries.asset_id）。 */
    @Column(name = "battery_asset_id", nullable = false)
    private Long batteryAssetId;

    @Column(name = "bound_at", nullable = false)
    private Instant boundAt;

    /** 解绑时间；为空且 active=true 表示当前生效绑定。 */
    @Column(name = "unbound_at")
    private Instant unboundAt;

    /** 本次绑定期间的电池循环次数。 */
    @Column(name = "cycles", nullable = false)
    @Builder.Default
    private Integer cycles = 0;

    /** 是否当前生效绑定。 */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;
}
