package com.claw.server.domain.iot;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.IoTRequests;
import com.claw.server.common.dto.IoTViews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * IoT 服务（S5）：遥测/轨迹上报与查询。
 *
 * <p>技术文档 4.3：遥测 ≥10s 间隔上报，断网本地缓存 ≥72h 恢复后补传（时间戳去重）；
 * 轨迹用 asset_id + device_id 双写绑定，TimescaleDB 自动压缩。
 * 本版以 REST 模拟 MQTT 上报，一次上报同时更新最新遥测并落一条轨迹点。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IoTService {

    private final DeviceRepository deviceRepository;
    private final TelemetryLatestRepository telemetryLatestRepository;
    private final TrackRepository trackRepository;

    /** 遥测/轨迹上报：更新最新遥测 + 落一条轨迹点。 */
    @Transactional
    public IoTViews.TelemetryView reportTelemetry(IoTRequests.TelemetryReport req) {
        Device device = deviceRepository.findByImei(req.imei())
                .orElseThrow(() -> BizException.notFound("error.iot.device.not.found"));

        device.setLastOnlineAt(Instant.now());
        device.setUpdatedAt(Instant.now());
        deviceRepository.save(device);

        TelemetryLatest latest = telemetryLatestRepository.findByDeviceId(device.getId())
                .orElseGet(() -> TelemetryLatest.builder()
                        .deviceId(device.getId()).assetId(device.getAssetId()).build());
        latest.setAssetId(device.getAssetId());
        latest.setSpeed(req.speed());
        latest.setSoc(req.soc());
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

        log.info("遥测上报 device={} asset={} soc={} speed={}", device.getId(), device.getAssetId(),
                req.soc(), req.speed());
        return toView(latest);
    }

    /** 查询资产最新遥测。 */
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

    private IoTViews.TelemetryView toView(TelemetryLatest l) {
        return new IoTViews.TelemetryView(l.getAssetId(), l.getDeviceId(),
                l.getSpeed(), l.getSoc(), l.getTemp(), l.getHumid(),
                l.getFaults(), l.getLat(), l.getLng(), l.getReportedAt());
    }
}
