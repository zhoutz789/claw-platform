package com.claw.server.domain.iot;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.IoTViews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * BMS 下行控制服务（锂电池 BMS 对接方案 Phase C）。
 *
 * <p>在既有 {@link DeviceCommandService}（签名 + 审计 + 已签名下行通道 + cmd_ack 关联）之上，
 * 提供 BMS 语义化的控制方法：充电/放电使能、加热（冷季）/水冷（热季）使能、风扇转速、
 * 强制均衡、换电锁仓。所有指令仅对 {@code device_type=BATTERY_BMS} 设备生效。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BmsControlService {

    private static final String BMS_DEVICE_TYPE = "BATTERY_BMS";

    private final DeviceRepository deviceRepository;
    private final DeviceCommandService deviceCommandService;

    /** 通用下发入口：校验设备类型 + 构造参数 + 委托 DeviceCommandService 签名/审计/发布。 */
    @Transactional
    public IoTViews.CommandView issueCommand(String deviceNo, BmsCommand cmd, Map<String, Object> params) {
        Device device = deviceRepository.findByDeviceNo(deviceNo)
                .orElseThrow(() -> BizException.notFound("error.iot.device.not.found"));
        if (!BMS_DEVICE_TYPE.equals(device.getDeviceType())) {
            throw BizException.invalidParam("error.bms.device.not.bms");
        }
        return deviceCommandService.issue(deviceNo, cmd.action, cmd.toParams(params));
    }

    // —— 类型化便捷方法（供内部服务 / 前端按语义调用）——
    public IoTViews.CommandView enableCharge(String deviceNo) {
        return issueCommand(deviceNo, BmsCommand.CHARGE_ENABLE, null);
    }

    public IoTViews.CommandView disableCharge(String deviceNo) {
        return issueCommand(deviceNo, BmsCommand.CHARGE_DISABLE, null);
    }

    public IoTViews.CommandView enableDischarge(String deviceNo) {
        return issueCommand(deviceNo, BmsCommand.DISCHARGE_ENABLE, null);
    }

    public IoTViews.CommandView disableDischarge(String deviceNo) {
        return issueCommand(deviceNo, BmsCommand.DISCHARGE_DISABLE, null);
    }

    public IoTViews.CommandView heaterOn(String deviceNo) {
        return issueCommand(deviceNo, BmsCommand.HEATER_ON, null);
    }

    public IoTViews.CommandView heaterOff(String deviceNo) {
        return issueCommand(deviceNo, BmsCommand.HEATER_OFF, null);
    }

    /** 冷季加热：设置目标温度（℃）。 */
    public IoTViews.CommandView setHeaterTemp(String deviceNo, BigDecimal targetTemp) {
        Map<String, Object> p = new HashMap<>();
        p.put("targetTemp", targetTemp);
        return issueCommand(deviceNo, BmsCommand.SET_HEATER_TEMP, p);
    }

    public IoTViews.CommandView coolingOn(String deviceNo) {
        return issueCommand(deviceNo, BmsCommand.COOLING_ON, null);
    }

    public IoTViews.CommandView coolingOff(String deviceNo) {
        return issueCommand(deviceNo, BmsCommand.COOLING_OFF, null);
    }

    /** 风扇转速（0–100%）。 */
    public IoTViews.CommandView setFanSpeed(String deviceNo, Integer fanSpeedPct) {
        Map<String, Object> p = new HashMap<>();
        p.put("fanSpeed", fanSpeedPct);
        return issueCommand(deviceNo, BmsCommand.SET_FAN_SPEED, p);
    }

    public IoTViews.CommandView forceBalance(String deviceNo) {
        return issueCommand(deviceNo, BmsCommand.FORCE_BALANCE, null);
    }

    /** 换电锁仓（取/还时由换电流程触发，不直接暴露给用户）。 */
    public IoTViews.CommandView lockSlot(String deviceNo) {
        return issueCommand(deviceNo, BmsCommand.LOCK_SLOT, null);
    }
}
