package com.claw.server.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 车辆 ↔ 电池 绑定仓储。
 */
public interface VehicleBatteryBindingRepository extends JpaRepository<VehicleBatteryBinding, Long> {

    /** 车辆的当前生效绑定（未解绑）。 */
    Optional<VehicleBatteryBinding> findByVehicleIdAndUnboundAtIsNull(Long vehicleId);

    /** 车辆绑定历史（按绑定时间倒序）。 */
    List<VehicleBatteryBinding> findByVehicleIdOrderByBoundAtDesc(Long vehicleId);

    /** 电池绑定历史（按绑定时间倒序）。 */
    List<VehicleBatteryBinding> findByBatteryIdOrderByBoundAtDesc(Long batteryId);
}
