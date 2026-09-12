package com.claw.server.domain.asset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 车辆 ↔ 电池 绑定服务（换电/充电三视图基础）。
 *
 * <p>绑定为「关闭旧 + 开新」语义：同一车辆任一时刻至多一条当前生效绑定（unboundAt 为空）。
 * 协议一致性为软校验（非阻断）：仅告警，不阻止绑定。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleBatteryBindingService {

    private final VehicleBatteryBindingRepository bindingRepository;
    private final VehicleRepository vehicleRepository;
    private final BatteryRepository batteryRepository;

    /**
     * 绑定电池到车辆：若车辆已有当前绑定则先解绑（置 unboundAt），再写入新绑定。
     *
     * @param vehicleId   车辆 asset_id
     * @param batteryId   电池 asset_id
     * @param protocolVer 协议版本（可为空）
     * @return 新建的绑定记录
     */
    @Transactional
    public VehicleBatteryBinding bind(Long vehicleId, Long batteryId, String protocolVer) {
        Instant now = Instant.now();

        bindingRepository.findByVehicleIdAndUnboundAtIsNull(vehicleId)
                .ifPresent(prev -> {
                    prev.setUnboundAt(now);
                    bindingRepository.save(prev);
                    log.info("车辆 {} 解绑旧电池 {}", vehicleId, prev.getBatteryId());
                });

        softCheckProtocol(vehicleId, batteryId, protocolVer);

        VehicleBatteryBinding binding = VehicleBatteryBinding.builder()
                .vehicleId(vehicleId)
                .batteryId(batteryId)
                .boundAt(now)
                .unboundAt(null)
                .protocolVer(protocolVer)
                .build();
        VehicleBatteryBinding saved = bindingRepository.save(binding);
        log.info("车辆 {} 绑定电池 {} protocolVer={}", vehicleId, batteryId, protocolVer);
        return saved;
    }

    /** 解绑当前电池（置 unboundAt，无当前绑定则幂等无操作）。 */
    @Transactional
    public void unbind(Long vehicleId) {
        bindingRepository.findByVehicleIdAndUnboundAtIsNull(vehicleId)
                .ifPresent(prev -> {
                    prev.setUnboundAt(Instant.now());
                    bindingRepository.save(prev);
                    log.info("车辆 {} 解绑电池 {}", vehicleId, prev.getBatteryId());
                });
    }

    /** 当前绑定电池 id（无则 empty）。 */
    @Transactional(readOnly = true)
    public Optional<Long> getCurrentBatteryId(Long vehicleId) {
        return bindingRepository.findByVehicleIdAndUnboundAtIsNull(vehicleId)
                .map(VehicleBatteryBinding::getBatteryId);
    }

    /** 绑定历史（按绑定时间倒序）。 */
    @Transactional(readOnly = true)
    public List<VehicleBatteryBinding> getHistory(Long vehicleId) {
        return bindingRepository.findByVehicleIdOrderByBoundAtDesc(vehicleId);
    }

    /** 协议软校验（非阻断）：vehicles.protocolVer 与 battery.protocolVer 不一致仅告警。 */
    private void softCheckProtocol(Long vehicleId, Long batteryId, String protocolVer) {
        try {
            String vehicleProto = vehicleRepository.findById(vehicleId)
                    .map(Vehicle::getProtocolVer).orElse(null);
            String batteryProto = batteryRepository.findById(batteryId)
                    .map(Battery::getProtocolVer).orElse(null);
            boolean mismatch = (vehicleProto != null && batteryProto != null)
                    && !vehicleProto.equalsIgnoreCase(batteryProto);
            if (mismatch) {
                log.warn("车辆 {} 与电池 {} 协议不匹配 vehicle={} battery={} 传入={}",
                        vehicleId, batteryId, vehicleProto, batteryProto, protocolVer);
            }
        } catch (Exception e) {
            log.warn("协议软校验跳过（非阻断）vehicle={} battery={}", vehicleId, batteryId, e);
        }
    }
}
