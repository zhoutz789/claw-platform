package com.claw.server.domain.iot;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.IoTRequests;
import com.claw.server.common.dto.IoTViews;
import com.claw.server.common.enums.LinkageDirection;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * IoT 服务（S5）：遥测/轨迹上报与查询。
 *
 * <p>技术文档 4.3：遥测 ≥10s 间隔上报，断网本地缓存 ≥72h 恢复后补传（时间戳去重）；
 * 轨迹用 asset_id + device_id 双写绑定。落库后触发四向联动编排。
 *
 * <p>本版同时支持两条链路：
 * <ul>
 *   <li>老 BMS 链路：{@link #reportTelemetry}（imei 帧，REST 模拟 / 老 EMQX 主题）</li>
 *   <li>车辆终端契约：{@link #reportLocation} / {@link #reportStatus}（deviceNo 身份，claw/iot/#/up）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IoTService {

    private final DeviceRepository deviceRepository;
    private final TelemetryLatestRepository telemetryLatestRepository;
    private final TrackRepository trackRepository;
    private final DeviceLinkageEventRepository linkageEventRepository;
    private final TelemetryLinkageService linkageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 遥测/轨迹上报（老 BMS 链路）：更新最新遥测 + 落一条轨迹点 + 驱动四向联动。 */
    @Transactional
    public IoTViews.TelemetryView reportTelemetry(IoTRequests.TelemetryReport req) {
        Device device = deviceRepository.findByImei(req.imei())
                .orElseThrow(() -> BizException.notFound("error.iot.device.not.found"));

        device.setLastOnlineAt(Instant.now());
        device.setUpdatedAt(Instant.now());
        deviceRepository.save(device);

        TelemetryLatest latest = upsert(device);
        latest.setAssetId(device.getAssetId());
        latest.setSpeed(req.speed());
        latest.setSoc(req.soc());
        latest.setSoh(req.soh());
        latest.setTemp(req.temp());
        latest.setHumid(req.humid());
        latest.setFaults(req.faults());
        latest.setLat(req.lat());
        latest.setLng(req.lng());
        latest.setReportedAt(Instant.now());
        telemetryLatestRepository.save(latest);

        trackRepository.save(Track.builder()
                .deviceId(device.getId()).assetId(device.getAssetId()).ts(Instant.now())
                .lat(req.lat()).lng(req.lng()).speed(req.speed()).soc(req.soc()).build());

        linkageService.evaluate(latest, device);

        log.info("遥测上报 device={} asset={} soc={} soh={}", device.getId(), device.getAssetId(),
                req.soc(), req.soh());
        return toView(latest);
    }

    /** 定位上报（车辆契约 location 报文）：更新最新遥测定位字段 + 落轨迹点。 */
    @Transactional
    public void reportLocation(IoTRequests.VehicleLocation req) {
        Device device = resolve(req.deviceId());
        device.setLastOnlineAt(Instant.now());
        device.setUpdatedAt(Instant.now());
        deviceRepository.save(device);

        TelemetryLatest latest = upsert(device);
        latest.setLat(req.lat());
        latest.setLng(req.lng());
        latest.setSpeed(req.speed());
        latest.setCourse(req.course());
        latest.setAltitude(req.alt());
        latest.setAcc(req.acc());
        latest.setBatteryVoltage(req.battery());
        latest.setRssi(req.rssi());
        latest.setReportedAt(Instant.now());
        telemetryLatestRepository.save(latest);

        trackRepository.save(Track.builder()
                .deviceId(device.getId()).assetId(device.getAssetId()).ts(Instant.now())
                .lat(req.lat()).lng(req.lng()).speed(req.speed()).soc(null).build());

        linkageService.evaluate(latest, device);
        log.info("定位上报 deviceNo={} asset={} lat={} lng={}", req.deviceId(), device.getAssetId(), req.lat(), req.lng());
    }

    /** 状态上报（车辆契约 status 报文）：更新开关/门磁/震动/告警等。 */
    @Transactional
    public void reportStatus(IoTRequests.VehicleStatus req) {
        Device device = resolve(req.deviceId());
        device.setLastOnlineAt(Instant.now());
        device.setUpdatedAt(Instant.now());
        deviceRepository.save(device);

        TelemetryLatest latest = upsert(device);
        latest.setRelayState(req.relay());
        latest.setDoorState(req.door());
        latest.setVibState(req.vib());
        latest.setTemp(req.temp());
        latest.setAlarms(toJson(req.alarms()));
        latest.setReportedAt(Instant.now());
        telemetryLatestRepository.save(latest);

        linkageService.evaluate(latest, device);
        log.info("状态上报 deviceNo={} relay={} alarms={}", req.deviceId(), req.relay(), req.alarms());
    }

    /** 查询设备最新状态（车辆契约）。 */
    @Transactional(readOnly = true)
    public IoTViews.DeviceStatusView deviceStatus(String deviceNo) {
        Device device = deviceRepository.findByDeviceNo(deviceNo)
                .orElseThrow(() -> BizException.notFound("error.iot.device.not.found"));
        return telemetryLatestRepository.findByDeviceId(device.getId())
                .map(l -> new IoTViews.DeviceStatusView(l.getDeviceId(), deviceNo,
                        l.getRelayState(), l.getAcc(), l.getBatteryVoltage(), l.getRssi(),
                        l.getDoorState(), l.getVibState(), l.getAlarms(), l.getReportedAt()))
                .orElse(null);
    }

    /** 查询资产最新遥测（老链路）。 */
    @Transactional(readOnly = true)
    public IoTViews.TelemetryView latest(Long assetId) {
        return telemetryLatestRepository.findByAssetId(assetId)
                .map(this::toView).orElse(null);
    }

    /** 查询资产轨迹（时间区间）。 */
    @Transactional(readOnly = true)
    public List<IoTViews.TrackView> tracks(Long assetId, Instant from, Instant to) {
        Instant f = from != null ? from : Instant.now().minusSeconds(86400);
        Instant t = to != null ? to : Instant.now();
        return trackRepository.findByAssetIdAndTsBetweenOrderByTsAsc(assetId, f, t).stream()
                .map(tr -> new IoTViews.TrackView(tr.getAssetId(), tr.getTs(),
                        tr.getLat(), tr.getLng(), tr.getSpeed(), tr.getSoc()))
                .toList();
    }

    /** 查询资产联动事件（四向闭环审计）；direction 为空则返回全部。 */
    @Transactional(readOnly = true)
    public List<IoTViews.LinkageView> linkageEvents(Long assetId, LinkageDirection direction) {
        List<DeviceLinkageEvent> list = direction != null
                ? linkageEventRepository.findByAssetIdAndDirectionOrderByTriggeredAtDesc(assetId, direction)
                : linkageEventRepository.findByAssetIdOrderByTriggeredAtDesc(assetId);
        return list.stream().map(this::toLinkageView).toList();
    }

    private Device resolve(String deviceNo) {
        if (deviceNo == null || deviceNo.isBlank()) {
            throw BizException.notFound("error.iot.device.not.found");
        }
        return deviceRepository.findByDeviceNo(deviceNo)
                .orElseThrow(() -> BizException.notFound("error.iot.device.not.found"));
    }

    private TelemetryLatest upsert(Device device) {
        return telemetryLatestRepository.findByDeviceId(device.getId())
                .orElseGet(() -> TelemetryLatest.builder()
                        .deviceId(device.getId()).assetId(device.getAssetId()).build());
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private IoTViews.TelemetryView toView(TelemetryLatest l) {
        return new IoTViews.TelemetryView(l.getAssetId(), l.getDeviceId(),
                l.getSpeed(), l.getSoc(), l.getSoh(), l.getTemp(), l.getHumid(),
                l.getFaults(), l.getLat(), l.getLng(), l.getReportedAt());
    }

    private IoTViews.LinkageView toLinkageView(DeviceLinkageEvent e) {
        return new IoTViews.LinkageView(e.getId(), e.getAssetId(), e.getDeviceId(),
                e.getDirection() != null ? e.getDirection().name() : null,
                e.getTriggerType() != null ? e.getTriggerType().name() : null,
                e.getPayload(), e.getStatus() != null ? e.getStatus().name() : null,
                e.getTriggeredAt());
    }
}
