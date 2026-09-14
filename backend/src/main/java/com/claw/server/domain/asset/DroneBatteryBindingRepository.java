package com.claw.server.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 无人机 ↔ 电池 绑定仓储（对应 claw.drone_battery_binding）。
 */
public interface DroneBatteryBindingRepository extends JpaRepository<DroneBatteryBinding, Long> {

    /** 无人机的当前生效绑定（active=true，按绑定时间倒序取最近一条）。 */
    Optional<DroneBatteryBinding> findFirstByDroneAssetIdAndActiveTrueOrderByBoundAtDesc(Long droneAssetId);

    /** 无人机绑定历史（按绑定时间倒序）。 */
    List<DroneBatteryBinding> findByDroneAssetIdOrderByBoundAtDesc(Long droneAssetId);

    /** 电池被绑历史（按绑定时间倒序）。 */
    List<DroneBatteryBinding> findByBatteryAssetIdOrderByBoundAtDesc(Long batteryAssetId);

    /** 当前生效绑定总数（供需视图：在用电池数）。 */
    long countByActiveTrue();

    /** 指定电池当前是否被占用（供需视图 / 绑定前置校验）。 */
    boolean existsByBatteryAssetIdAndActiveTrue(Long batteryAssetId);
}
