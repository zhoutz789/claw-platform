package com.claw.server.domain.risk;

import com.claw.server.domain.asset.VehicleCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 欠费断缴锁车钩子（T3 风控↔控车接缝）。
 *
 * <p>风控域（含未来的车辆欠费检测 / 现有站点风控）判定欠费后，调用本触发器，
 * 经 {@link VehicleCommandService#lockForPaymentDefault(Long)} 对车辆下发断缴锁车指令。
 * 该指令走 iot 域既有签名下行通道，并落入 {@code device_commands} 指令日志（EMQX 未启用时仅落库）。
 *
 * <p>类比无人机 LOW_BATTERY / LOST_LINK 锁机禁行——平台对资产保有最终控制权。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentDefaultLockTrigger {

    private final VehicleCommandService vehicleCommandService;

    @Transactional
    public void onPaymentDefault(Long assetId) {
        log.warn("[RISK-LOCK] 欠费断缴锁车钩子触发 assetId={}", assetId);
        vehicleCommandService.lockForPaymentDefault(assetId);
    }
}
