package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.AirspaceLevel;
import com.claw.server.common.enums.FlightPlanStatus;
import com.claw.server.common.enums.PilotLicenseType;
import com.claw.server.domain.airspace.AirspaceZone;
import com.claw.server.domain.airspace.AirspaceZoneRepository;
import com.claw.server.domain.airspace.FlightPlan;
import com.claw.server.domain.airspace.FlightPlanRepository;
import com.claw.server.domain.airspace.PilotLicense;
import com.claw.server.domain.airspace.PilotLicenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 低空域管理：空域分区、飞行计划、飞手资质。
 * 飞行计划提交时校验空域等级（仅 OPERATIONAL 可放行），是无人机合规前置。
 */
@RestController
@RequestMapping("/api/v1/airspace")
@RequiredArgsConstructor
public class AirspaceController {

    private final AirspaceZoneRepository zoneRepository;
    private final FlightPlanRepository flightPlanRepository;
    private final PilotLicenseRepository licenseRepository;

    @PostMapping("/zones")
    public ApiResult<AirspaceZone> createZone(@RequestBody CreateZone req) {
        AirspaceZone z = AirspaceZone.builder()
                .name(req.name()).level(req.level()).centerLat(req.lat()).centerLng(req.lng())
                .radiusM(req.radiusM()).country(req.country() != null ? req.country() : "KH")
                .note(req.note()).build();
        return ApiResult.ok(zoneRepository.save(z));
    }

    @GetMapping("/zones")
    public ApiResult<List<AirspaceZone>> listZones() {
        return ApiResult.ok(zoneRepository.findAll());
    }

    /**
     * 提交飞行计划。合规前置：空域必须存在且为 OPERATIONAL（可飞作业区）。
     *
     * <p>两处校验原本抛裸 {@code IllegalArgumentException}，全局异常处理器无对应
     * handler，兜成 500 + {@code "internal error"} —— 前端无法区分「空域不存在」
     * 「空域不可飞」和「服务真的挂了」，三种情况只能统一提示「操作失败」（N1）。
     * 改为语义化业务码后：
     * <ul>
     *   <li>40470 → HTTP 404，空域不存在（落在 httpStatus() 的 404xx 整段分支）；</li>
     *   <li>40960 → HTTP 409，空域存在但等级不符（状态冲突）。</li>
     * </ul>
     *
     * @throws BizException 40470 error.drone.zone.not.found（zone 不存在）
     * @throws BizException 40960 error.drone.zone.not.operational（zone 非 OPERATIONAL）
     */
    @PostMapping("/flight-plans")
    public ApiResult<FlightPlan> createFlightPlan(@RequestBody CreateFlightPlan req) {
        AirspaceZone zone = zoneRepository.findById(req.zoneId())
                .orElseThrow(() -> BizException.of(40470, "error.drone.zone.not.found"));
        if (zone.getLevel() != AirspaceLevel.OPERATIONAL) {
            throw BizException.of(40960, "error.drone.zone.not.operational", zone.getLevel());
        }
        FlightPlan fp = FlightPlan.builder()
                .assetId(req.assetId()).zoneId(req.zoneId()).pilotId(req.pilotId())
                .plannedAt(req.plannedAt() != null ? req.plannedAt() : Instant.now())
                .status(FlightPlanStatus.APPROVED).routeNote(req.routeNote()).build();
        return ApiResult.ok(flightPlanRepository.save(fp));
    }

    @GetMapping("/flight-plans")
    public ApiResult<List<FlightPlan>> listFlightPlans() {
        return ApiResult.ok(flightPlanRepository.findAll());
    }

    /**
     * 登记飞手资质。
     *
     * <p>{@code pilot_licenses.license_no} 有唯一约束，重复登记会撞
     * {@code DataIntegrityViolationException} → 500。这里先预检，命中则抛
     * 40962（HTTP 409，状态冲突），客户端可直接提示「执照编号已存在，请核对后重试」。
     *
     * <p>预检不能替代唯一约束：并发下两个请求可能同时通过预检，仍由数据库兜底。
     * 预检的价值是把「可预期的用户输入冲突」变成明确的 409，而不是丢一个 500。
     *
     * @throws BizException 40962 error.drone.license.duplicate（执照号已存在）
     */
    @PostMapping("/pilot-licenses")
    public ApiResult<PilotLicense> createLicense(@RequestBody CreateLicense req) {
        if (licenseRepository.existsByLicenseNo(req.licenseNo())) {
            throw BizException.of(40962, "error.drone.license.duplicate", req.licenseNo());
        }
        PilotLicense l = PilotLicense.builder()
                .licenseNo(req.licenseNo()).holderName(req.holderName()).ltype(req.ltype())
                .issuer(req.issuer() != null ? req.issuer() : "SSCA")
                .expiryDate(req.expiryDate()).build();
        return ApiResult.ok(licenseRepository.save(l));
    }

    @GetMapping("/pilot-licenses")
    public ApiResult<List<PilotLicense>> listLicenses() {
        return ApiResult.ok(licenseRepository.findAll());
    }

    public record CreateZone(String name, AirspaceLevel level, BigDecimal lat, BigDecimal lng,
                             Integer radiusM, String country, String note) {
    }

    public record CreateFlightPlan(Long assetId, Long zoneId, Long pilotId, Instant plannedAt, String routeNote) {
    }

    public record CreateLicense(String licenseNo, String holderName, PilotLicenseType ltype,
                                String issuer, LocalDate expiryDate) {
    }
}
