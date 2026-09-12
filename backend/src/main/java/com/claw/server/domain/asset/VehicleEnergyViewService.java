package com.claw.server.domain.asset;

import com.claw.server.domain.station.ChargeSession;
import com.claw.server.domain.swap.SwapOrder;
import com.claw.server.domain.swap.SwapOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 车辆能源三视图服务（只读聚合）。
 *
 * <p>当前电池来自绑定表；近期换电来自 SwapOrder（含 vehicleId）；近期充电因 ChargeSession
 * 仅记录被充电池 asset_id、无 vehicle FK，本视图返回空列表，避免臆造车辆↔充电关联。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleEnergyViewService {

    private final VehicleBatteryBindingService bindingService;
    private final SwapOrderRepository swapOrderRepository;

    /**
     * 取车辆能源三视图（只读）。
     *
     * @param vehicleId 车辆 asset_id
     * @return 三视图聚合
     */
    @Transactional(readOnly = true)
    public VehicleEnergyView getVehicleEnergyView(Long vehicleId) {
        Long currentBatteryId = bindingService.getCurrentBatteryId(vehicleId).orElse(null);

        // ChargeSession 无 vehicle FK（仅 asset_id 指向被充电池），不臆造关联，返回空。
        List<ChargeSession> recentCharges = List.of();

        List<SwapOrder> recentSwaps = swapOrderRepository.findByVehicleIdOrderByCreatedAtDesc(vehicleId);

        return VehicleEnergyView.builder()
                .currentBatteryId(currentBatteryId)
                .recentCharges(recentCharges)
                .recentSwaps(recentSwaps)
                .build();
    }
}
