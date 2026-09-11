package com.claw.server.domain.iot;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.BmsTelemetryReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * BMS 遥测落库服务（锂电池 BMS 对接方案 Phase A）。
 *
 * <p>把 {@link BmsAdapter} 归一化的 {@link BmsTelemetryReport} 写入：
 * <ul>
 *   <li>{@code telemetry_latest}：覆盖 BMS 实时列（pack_voltage / current_a / ccl / dcl / 温度探头数组等）</li>
 *   <li>{@code tracks}：落一条带 soc 的轨迹点（复用既有双写绑定）</li>
 * </ul>
 * 仅写入非空字段，避免把"未上报"误覆盖为 null。Phase A 暂不触发车辆联动编排
 * （{@code TelemetryLinkageService} 为车辆契约设计），BMS 专属联动在 Phase D 业务接线阶段补齐。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BmsTelemetryService {

    private final DeviceRepository deviceRepository;
    private final TelemetryLatestRepository telemetryLatestRepository;
    private final TrackRepository trackRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public void handleReport(BmsTelemetryReport r, String topicDeviceNo) {
        String deviceNo = r.deviceNo() != null ? r.deviceNo() : topicDeviceNo;
        if (deviceNo == null || deviceNo.isBlank()) {
            throw BizException.invalidParam("error.bms.device.no.missing");
        }
        Device device = deviceRepository.findByDeviceNo(deviceNo)
                .orElseThrow(() -> BizException.notFound("error.iot.device.not.found"));

        device.setLastOnlineAt(Instant.now());
        device.setUpdatedAt(Instant.now());
        deviceRepository.save(device);

        TelemetryLatest latest = telemetryLatestRepository.findByDeviceId(device.getId())
                .orElseGet(() -> TelemetryLatest.builder()
                        .deviceId(device.getId()).assetId(device.getAssetId()).build());

        latest.setAssetId(device.getAssetId());
        latest.setReportedAt(Instant.now());

        set(latest::setSoc, r.soc());
        set(latest::setSoh, r.soh());
        set(latest::setPackVoltage, r.packVoltage());
        set(latest::setCurrentA, r.currentA());
        set(latest::setPowerW, r.powerW());
        set(latest::setCcl, r.ccl());
        set(latest::setDcl, r.dcl());
        set(latest::setCvl, r.cvl());
        set(latest::setRemainingCapacityAh, r.remainingCapacityAh());
        set(latest::setFullChargeCapacityAh, r.fullChargeCapacityAh());
        set(latest::setTempMax, r.tempMax());
        set(latest::setTempMin, r.tempMin());
        set(latest::setTempMaxId, r.tempMaxId());
        set(latest::setTempMinId, r.tempMinId());
        set(latest::setTemperaturesJson, toJson(r.temperatures()));
        set(latest::setCellVoltagesJson, toJson(r.cellVoltages()));
        set(latest::setBalanceStatus, r.balanceStatus());
        set(latest::setBalanceCurrent, r.balanceCurrent());
        set(latest::setCellVoltageSpread, r.cellVoltageSpread());
        set(latest::setChargeEnable, r.chargeEnable());
        set(latest::setDischargeEnable, r.dischargeEnable());
        set(latest::setHeaterEnable, r.heaterEnable());
        set(latest::setWaterCoolingEnable, r.waterCoolingEnable());
        set(latest::setFanSpeed, r.fanSpeed());
        set(latest::setCoolantTempIn, r.coolantTempIn());
        set(latest::setCoolantTempOut, r.coolantTempOut());
        set(latest::setSatelliteCount, r.satelliteCount());
        set(latest::setLat, r.lat());
        set(latest::setLng, r.lng());
        set(latest::setLastFixTime, toInstant(r.lastFixTime()));
        set(latest::setBmsState, r.bmsState());

        telemetryLatestRepository.save(latest);

        trackRepository.save(Track.builder()
                .deviceId(device.getId()).assetId(device.getAssetId()).ts(Instant.now())
                .lat(r.lat()).lng(r.lng()).speed(r.speed()).soc(r.soc()).build());

        log.info("BMS 遥测上报 deviceNo={} asset={} soc={} soh={} packV={} cur={}",
                deviceNo, device.getAssetId(), r.soc(), r.soh(), r.packVoltage(), r.currentA());
    }

    @FunctionalInterface
    private interface Setter<T> {
        void accept(T v);
    }

    private <T> void set(Setter<T> setter, T value) {
        if (value != null) setter.accept(value);
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private Instant toInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
