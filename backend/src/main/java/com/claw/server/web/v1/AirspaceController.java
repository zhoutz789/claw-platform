package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
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

    @PostMapping("/flight-plans")
    public ApiResult<FlightPlan> createFlightPlan(@RequestBody CreateFlightPlan req) {
        AirspaceZone zone = zoneRepository.findById(req.zoneId())
                .orElseThrow(() -> new IllegalArgumentException("airspace zone not found"));
        if (zone.getLevel() != AirspaceLevel.OPERATIONAL) {
            throw new IllegalArgumentException("空域非可飞作业区，飞行计划不予批准");
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

    @PostMapping("/pilot-licenses")
    public ApiResult<PilotLicense> createLicense(@RequestBody CreateLicense req) {
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
