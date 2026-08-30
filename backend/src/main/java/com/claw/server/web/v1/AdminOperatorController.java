package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.enums.OperatorAccountType;
import com.claw.server.common.enums.OperatorType;
import com.claw.server.common.enums.RiskEventType;
import com.claw.server.common.enums.RiskSeverity;
import com.claw.server.domain.operator.OperatorAccount;
import com.claw.server.domain.operator.OperatorBond;
import com.claw.server.domain.operator.OperatorFinanceService;
import com.claw.server.domain.operator.OperatorKycRecord;
import com.claw.server.domain.operator.OperatorRiskEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import com.claw.server.common.security.RequirePermission;

/**
 * 后台运营方财务模块（Phase2 接线）：账户 + 保证金 + KYC + 风控事件。
 * 此前 OperatorFinanceService 真实业务逻辑因无 controller 入口而不可达，本控制器将其暴露为 API。
 */
@RestController
@RequestMapping("/api/v1/admin/operator")
@RequiredArgsConstructor
public class AdminOperatorController {

    private final OperatorFinanceService operatorFinanceService;

    @PostMapping("/accounts")
    @RequirePermission("operator:create")
    public ApiResult<OperatorAccount> createAccount(@RequestBody AccountReq req) {
        return ApiResult.ok(operatorFinanceService.createAccount(req.operatorId(), req.stationId(),
                OperatorAccountType.valueOf(req.type())));
    }

    @GetMapping("/accounts")
    public ApiResult<List<OperatorAccount>> listAccounts(@RequestParam Long operatorId) {
        return ApiResult.ok(operatorFinanceService.listAccounts(operatorId));
    }

    @PostMapping("/bonds")
    @RequirePermission("operator:create")
    public ApiResult<OperatorBond> upsertBond(@RequestBody BondReq req) {
        return ApiResult.ok(operatorFinanceService.upsertBond(req.operatorId(), req.stationId(),
                OperatorType.valueOf(req.operatorType()), req.baseBond(), req.managedAssetValue(), req.bondRate()));
    }

    @PostMapping("/bonds/{id}/post")
    @RequirePermission("operator:create")
    public ApiResult<OperatorBond> postBond(@PathVariable Long id, @RequestParam BigDecimal amount) {
        return ApiResult.ok(operatorFinanceService.postBond(id, amount));
    }

    @PostMapping("/kyc/{kycId}/approve")
    @RequirePermission("operator:create")
    public ApiResult<OperatorKycRecord> approveKyc(@PathVariable Long kycId, @RequestParam Long approvedBy) {
        return ApiResult.ok(operatorFinanceService.approveKyc(kycId, approvedBy));
    }

    @PostMapping("/kyc/{kycId}/reject")
    @RequirePermission("operator:create")
    public ApiResult<OperatorKycRecord> rejectKyc(@PathVariable Long kycId, @RequestParam Long approvedBy, @RequestParam String reason) {
        return ApiResult.ok(operatorFinanceService.rejectKyc(kycId, approvedBy, reason));
    }

    @PostMapping("/risk-events")
    @RequirePermission("operator:create")
    public ApiResult<OperatorRiskEvent> recordRiskEvent(@RequestBody RiskEventReq req) {
        return ApiResult.ok(operatorFinanceService.recordRiskEvent(req.operatorId(), req.stationId(),
                RiskEventType.valueOf(req.eventType()), RiskSeverity.valueOf(req.severity()),
                req.description(), req.detectedValue(), req.expectedValue()));
    }

    @PostMapping("/risk-events/{id}/resolve")
    @RequirePermission("operator:create")
    public ApiResult<OperatorRiskEvent> resolveEvent(@PathVariable Long id,
                                                     @RequestParam Long resolvedBy, @RequestParam(required = false) String note) {
        return ApiResult.ok(operatorFinanceService.resolveEvent(id, resolvedBy, note));
    }

    @GetMapping("/risk-events/unresolved")
    public ApiResult<List<OperatorRiskEvent>> unresolved() {
        return ApiResult.ok(operatorFinanceService.listUnresolved());
    }

    public record AccountReq(Long operatorId, Long stationId, String type) {
    }

    public record BondReq(Long operatorId, Long stationId, String operatorType, BigDecimal baseBond,
                          BigDecimal managedAssetValue, BigDecimal bondRate) {
    }

    public record RiskEventReq(Long operatorId, Long stationId, String eventType, String severity,
                               String description, BigDecimal detectedValue, BigDecimal expectedValue) {
    }
}
