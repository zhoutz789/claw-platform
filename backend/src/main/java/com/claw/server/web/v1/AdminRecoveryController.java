package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.enums.BlacklistType;
import com.claw.server.domain.recovery.ClawScore;
import com.claw.server.domain.recovery.RecoveryOrder;
import com.claw.server.domain.recovery.RecoveryService;
import com.claw.server.domain.recovery.ResidualValuation;
import com.claw.server.domain.recovery.StationBlacklist;
import com.claw.server.domain.recovery.StationBlacklistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 后台残值回收模块（Phase2 接线）：估价三方流程 + 回收/以旧换新 + 信用分 + 黑名单。
 * 此前 RecoveryService 真实业务逻辑因无 controller 入口而不可达，本控制器将其暴露为 API。
 */
@RestController
@RequestMapping("/api/v1/admin/recovery")
@RequiredArgsConstructor
public class AdminRecoveryController {

    private final RecoveryService recoveryService;
    private final StationBlacklistRepository blacklistRepository;

    @PostMapping("/valuations")
    public ApiResult<ResidualValuation> createValuation(@RequestBody ValuationReq req) {
        return ApiResult.ok(recoveryService.createValuation(req.assetId(), req.ownerUserId(),
                req.soh(), req.usageYears(), req.brand(), req.model(), req.cycleCount()));
    }

    @PostMapping("/valuations/system-estimate")
    public ApiResult<ResidualValuation> systemEstimate(@RequestParam Long valuationId, @RequestParam BigDecimal estimate) {
        return ApiResult.ok(recoveryService.submitSystemEstimate(valuationId, estimate));
    }

    @PostMapping("/valuations/station-estimate")
    public ApiResult<ResidualValuation> stationEstimate(@RequestParam Long valuationId, @RequestParam BigDecimal estimate) {
        return ApiResult.ok(recoveryService.submitStationEstimate(valuationId, estimate));
    }

    @PostMapping("/valuations/third-party-estimate")
    public ApiResult<ResidualValuation> thirdPartyEstimate(@RequestParam Long valuationId, @RequestParam BigDecimal estimate,
                                                          @RequestParam String thirdPartyName, @RequestParam(required = false) String reportUrl) {
        return ApiResult.ok(recoveryService.submitThirdPartyEstimate(valuationId, estimate, thirdPartyName, reportUrl));
    }

    @PostMapping("/valuations/{valuationId}/finalize")
    public ApiResult<ResidualValuation> finalize(@PathVariable Long valuationId) {
        return ApiResult.ok(recoveryService.finalizeValuation(valuationId));
    }

    @PostMapping("/cash")
    public ApiResult<RecoveryOrder> createCash(@RequestBody CashReq req) {
        return ApiResult.ok(recoveryService.createCashRecovery(req.assetId(), req.ownerUserId(),
                req.valuationId(), req.ownershipId(), req.recoveryPrice(), req.processingFee()));
    }

    @PostMapping("/trade-in")
    public ApiResult<RecoveryOrder> createTradeIn(@RequestBody TradeInReq req) {
        return ApiResult.ok(recoveryService.createTradeIn(req.assetId(), req.ownerUserId(), req.valuationId(),
                req.ownershipId(), req.oldValuation(), req.newAssetId(), req.newAssetPrice()));
    }

    @PostMapping("/orders/{orderId}/confirm")
    public ApiResult<RecoveryOrder> confirm(@PathVariable Long orderId) {
        return ApiResult.ok(recoveryService.confirmRecovery(orderId));
    }

    @PostMapping("/orders/{orderId}/complete")
    public ApiResult<RecoveryOrder> complete(@PathVariable Long orderId, @RequestParam String ledgerTxnId) {
        return ApiResult.ok(recoveryService.completeRecovery(orderId, ledgerTxnId));
    }

    @GetMapping("/scores/{userId}")
    public ApiResult<ClawScore> score(@PathVariable Long userId) {
        return ApiResult.ok(recoveryService.getOrCreateScore(userId));
    }

    @PostMapping("/scores/{userId}")
    public ApiResult<ClawScore> updateScore(@PathVariable Long userId, @RequestParam int delta,
                                            @RequestParam String eventType, @RequestParam(required = false) String detail) {
        return ApiResult.ok(recoveryService.updateScore(userId, delta, eventType, detail));
    }

    @GetMapping("/can-open-station/{userId}")
    public ApiResult<Boolean> canOpen(@PathVariable Long userId) {
        return ApiResult.ok(recoveryService.canOpenStation(userId));
    }

    @GetMapping("/blacklist")
    public ApiResult<List<StationBlacklist>> listBlacklist() {
        return ApiResult.ok(blacklistRepository.findAll().stream()
                .filter(b -> !Boolean.TRUE.equals(b.getDeleted())).toList());
    }

    @PostMapping("/blacklist")
    public ApiResult<StationBlacklist> blacklist(@RequestBody BlacklistReq req) {
        return ApiResult.ok(recoveryService.blacklist(BlacklistType.valueOf(req.type()), req.targetId(),
                req.reason(), req.description(), req.blacklistedBy(), req.expiresAt()));
    }

    @PostMapping("/blacklist/{id}/resolve")
    public ApiResult<StationBlacklist> resolveBlacklist(@PathVariable Long id,
                                                        @RequestParam Long resolvedBy, @RequestParam(required = false) String note) {
        return ApiResult.ok(recoveryService.resolveBlacklist(id, resolvedBy, note));
    }

    public record ValuationReq(Long assetId, Long ownerUserId, BigDecimal soh, BigDecimal usageYears,
                               String brand, String model, Integer cycleCount) {
    }

    public record CashReq(Long assetId, Long ownerUserId, Long valuationId, Long ownershipId,
                          BigDecimal recoveryPrice, BigDecimal processingFee) {
    }

    public record TradeInReq(Long assetId, Long ownerUserId, Long valuationId, Long ownershipId,
                             BigDecimal oldValuation, Long newAssetId, BigDecimal newAssetPrice) {
    }

    public record BlacklistReq(String type, Long targetId, String reason, String description,
                               Long blacklistedBy, Instant expiresAt) {
    }
}
