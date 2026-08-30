package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.enums.ClaimType;
import com.claw.server.common.enums.InsuranceType;
import com.claw.server.domain.insurance.AccidentClaim;
import com.claw.server.domain.insurance.InsuranceService;
import com.claw.server.domain.insurance.VehicleInsurance;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import com.claw.server.common.security.RequirePermission;

/**
 * 后台保险模块（Phase2 接线）：投保 + 报案 + 定损 + 审批/拒赔。
 * 此前 InsuranceService 真实业务逻辑因无 controller 入口而不可达，本控制器将其暴露为 API。
 */
@RestController
@RequestMapping("/api/v1/admin/insurance")
@RequiredArgsConstructor
public class AdminInsuranceController {

    private final InsuranceService insuranceService;

    @PostMapping("/policies")
    @RequirePermission("insurance:create")
    public ApiResult<VehicleInsurance> createPolicy(@RequestBody PolicyReq req) {
        return ApiResult.ok(insuranceService.createPolicy(req.assetId(), req.ownerUserId(), req.templateId(),
                InsuranceType.valueOf(req.type()), req.provider(), req.coverage(), req.deductible(), req.monthlyPremium()));
    }

    @PostMapping("/claims")
    @RequirePermission("insurance:create")
    public ApiResult<AccidentClaim> fileClaim(@RequestBody ClaimReq req) {
        return ApiResult.ok(insuranceService.fileClaim(req.insuranceId(), req.assetId(), req.claimantUserId(),
                ClaimType.valueOf(req.claimType()), req.accidentDate(), req.location(), req.description(), req.damageAmount()));
    }

    @PostMapping("/claims/{id}/assess")
    @RequirePermission("insurance:create")
    public ApiResult<AccidentClaim> assess(@PathVariable Long id, @RequestParam Long reviewedBy,
                                           @RequestParam BigDecimal assessedAmount, @RequestParam(required = false) String notes) {
        return ApiResult.ok(insuranceService.assessClaim(id, reviewedBy, assessedAmount, notes));
    }

    @PostMapping("/claims/{id}/approve")
    @RequirePermission("insurance:create")
    public ApiResult<AccidentClaim> approve(@PathVariable Long id, @RequestParam String ledgerTxnId) {
        return ApiResult.ok(insuranceService.approveClaim(id, ledgerTxnId));
    }

    @PostMapping("/claims/{id}/reject")
    @RequirePermission("insurance:create")
    public ApiResult<AccidentClaim> reject(@PathVariable Long id, @RequestParam Long reviewedBy, @RequestParam String reason) {
        return ApiResult.ok(insuranceService.rejectClaim(id, reviewedBy, reason));
    }

    @GetMapping("/claims/pending")
    public ApiResult<List<AccidentClaim>> pendingClaims() {
        return ApiResult.ok(insuranceService.listPendingClaims());
    }

    @GetMapping("/active")
    public ApiResult<VehicleInsurance> activeInsurance(@RequestParam Long assetId) {
        return ApiResult.ok(insuranceService.getActiveInsurance(assetId));
    }

    public record PolicyReq(Long assetId, Long ownerUserId, Long templateId, String type, String provider,
                            BigDecimal coverage, BigDecimal deductible, BigDecimal monthlyPremium) {
    }

    public record ClaimReq(Long insuranceId, Long assetId, Long claimantUserId, String claimType,
                           Instant accidentDate, String location, String description, BigDecimal damageAmount) {
    }
}
