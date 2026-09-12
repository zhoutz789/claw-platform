package com.claw.server.domain.asset;

import com.claw.server.domain.station.ChargeSession;
import com.claw.server.domain.swap.SwapOrder;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 车辆能源三视图 DTO（只读聚合）。
 *
 * <ul>
 *   <li>{@code currentBatteryId}：当前绑定电池（来自 vehicle_battery_bindings）；</li>
 *   <li>{@code recentCharges}：近期充电会话（ChargeSession 无 vehicle FK，本视图返回空，不臆造关联）；</li>
 *   <li>{@code recentSwaps}：近期换电订单（SwapOrder.vehicleId 关联）。</li>
 * </ul>
 */
@Getter
@Builder
public class VehicleEnergyView {

    /** 当前绑定电池 asset_id；无绑定为 null。 */
    private final Long currentBatteryId;

    /** 近期充电会话（ChargeSession 无 vehicle FK，目前恒为空）。 */
    private final List<ChargeSession> recentCharges;

    /** 近期换电订单（按创建时间倒序）。 */
    private final List<SwapOrder> recentSwaps;
}
