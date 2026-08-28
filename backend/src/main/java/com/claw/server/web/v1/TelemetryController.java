package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.domain.iot.Telemetry;
import com.claw.server.domain.iot.TelemetryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * IoT 遥测快照：数字孪生数据源，也是生命周期扫描器 SOH/位置的读取来源。
 * 无真实设备时可由 {@code POST /push} 灌 Mock 数据，使「退役→回收」自动闭环可演练。
 */
@RestController
@RequestMapping("/api/v1/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryRepository telemetryRepository;

    /** 设备上报 / Mock 灌入最新快照（upsert by assetId）。 */
    @PostMapping("/push")
    public ApiResult<Telemetry> push(@RequestBody PushReq req) {
        Telemetry t = telemetryRepository.findByAssetId(req.assetId())
                .map(existing -> {
                    existing.setSoh(req.soh());
                    existing.setLat(req.lat());
                    existing.setLng(req.lng());
                    existing.setSpeedKph(req.speedKph());
                    existing.setUpdatedAt(java.time.Instant.now());
                    return telemetryRepository.save(existing);
                })
                .orElseGet(() -> telemetryRepository.save(Telemetry.builder()
                        .assetId(req.assetId()).soh(req.soh()).lat(req.lat())
                        .lng(req.lng()).speedKph(req.speedKph()).build()));
        return ApiResult.ok(t);
    }

    @GetMapping("/{assetId}")
    public ApiResult<Telemetry> get(@PathVariable Long assetId) {
        return ApiResult.ok(telemetryRepository.findByAssetId(assetId).orElse(null));
    }

    @GetMapping
    public ApiResult<List<Telemetry>> list() {
        return ApiResult.ok(telemetryRepository.findAll());
    }

    public record PushReq(Long assetId, BigDecimal soh, BigDecimal lat, BigDecimal lng, BigDecimal speedKph) {
    }
}
