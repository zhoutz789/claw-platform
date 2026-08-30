package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.AdminDtos.*;
import com.claw.server.common.enums.TransferType;
import com.claw.server.domain.custody.CustodyDispute;
import com.claw.server.domain.custody.CustodyDisputeRepository;
import com.claw.server.domain.custody.CustodyTransfer;
import com.claw.server.domain.custody.CustodyTransferAudit;
import com.claw.server.domain.custody.CustodyTransferAuditRepository;
import com.claw.server.domain.custody.CustodyTransferRepository;
import com.claw.server.domain.custody.CustodyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import com.claw.server.common.security.RequirePermission;

/**
 * 后台产权与争议模块（S5 补齐）：产权链追溯 + 争议仲裁。
 *
 * <p>GET    /custody/transfers              产权转移链列表
 * GET    /custody/transfers/{id}           产权转移详情（含审计轨迹）
 * GET    /custody/disputes                 争议列表
 * POST   /custody/disputes                 登记争议
 * GET    /custody/disputes/{id}           争议详情
 * POST   /custody/disputes/{id}/arbitrate 仲裁裁决
 */
@RestController
@RequestMapping("/api/v1/admin/custody")
@RequiredArgsConstructor
public class AdminCustodyController {

    private final CustodyTransferRepository transferRepository;
    private final CustodyTransferAuditRepository auditRepository;
    private final CustodyDisputeRepository disputeRepository;
    private final CustodyService custodyService;

    @GetMapping("/transfers")
    public ApiResult<List<CustodyTransferView>> listTransfers() {
        return ApiResult.ok(transferRepository.findAll().stream()
                .filter(t -> !Boolean.TRUE.equals(t.getDeleted()))
                .map(this::toTransferView).toList());
    }

    /**
     * 创建产权转移记录（S 级修复：此前产权链仅由 V19 种子演示数据写入，业务流从未调用 recordTransfer）。
     * 实际业务（换电/租赁/回收/以旧换新/共享池）应在各自完成后调用本端点，形成不可篡改产权链。
     */
    @PostMapping("/transfers")
    @RequirePermission("custody:create")
    public ApiResult<CustodyTransferView> createTransfer(@RequestBody CustodyTransferReq req) {
        CustodyTransfer t = custodyService.recordTransfer(req.assetId(), req.assetType(), req.fromUserId(),
                req.toUserId(), TransferType.valueOf(req.transferType()), req.stationId(), req.swapOrderId(),
                req.assetSoh(), req.assetSoc(), req.assetCycleCount());
        return ApiResult.ok(toTransferView(t));
    }

    @GetMapping("/transfers/{id}")
    public ApiResult<CustodyTransferDetailView> transferDetail(@PathVariable Long id) {
        CustodyTransfer t = transferRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "custody.transfer.not.found"));
        List<CustodyAuditView> audits = auditRepository.findByTransferIdAndDeletedFalse(id).stream()
                .map(this::toAuditView).toList();
        return ApiResult.ok(new CustodyTransferDetailView(toTransferView(t), audits));
    }

    @GetMapping("/disputes")
    public ApiResult<List<CustodyDisputeView>> listDisputes() {
        return ApiResult.ok(disputeRepository.findAll().stream()
                .filter(d -> !Boolean.TRUE.equals(d.getDeleted()))
                .map(this::toDisputeView).toList());
    }

    @PostMapping("/disputes")
    @RequirePermission("custody:create")
    public ApiResult<CustodyDisputeView> createDispute(@RequestBody CustodyDisputeReq req) {
        CustodyDispute d = CustodyDispute.builder()
                .transferId(req.transferId())
                .assetId(req.assetId())
                .claimantId(req.claimantId())
                .respondentId(req.respondentId())
                .disputeType(req.disputeType())
                .description(req.description())
                .evidenceUrls(req.evidenceUrls())
                .claimAmount(req.claimAmount())
                .status("PENDING")
                .tenantId(1L)
                .deleted(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return ApiResult.ok(toDisputeView(disputeRepository.save(d)));
    }

    @GetMapping("/disputes/{id}")
    public ApiResult<CustodyDisputeView> disputeDetail(@PathVariable Long id) {
        CustodyDispute d = disputeRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "custody.dispute.not.found"));
        return ApiResult.ok(toDisputeView(d));
    }

    @PostMapping("/disputes/{id}/arbitrate")
    @RequirePermission("custody:create")
    public ApiResult<CustodyDisputeView> arbitrate(@PathVariable Long id,
                                                   @RequestBody CustodyArbitrateReq req) {
        CustodyDispute d = disputeRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "custody.dispute.not.found"));
        d.setArbitratorId(req.arbitratorId());
        d.setAwardedAmount(req.awardedAmount());
        d.setResolution(req.resolution());
        d.setStatus("RESOLVED");
        d.setResolvedAt(Instant.now());
        d.setUpdatedAt(Instant.now());
        return ApiResult.ok(toDisputeView(disputeRepository.save(d)));
    }

    @DeleteMapping("/disputes/{id}")
    @RequirePermission("custody:delete")
    public ApiResult<Void> deleteDispute(@PathVariable Long id) {
        CustodyDispute d = disputeRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "custody.dispute.not.found"));
        d.setDeleted(true);
        d.setUpdatedAt(Instant.now());
        disputeRepository.save(d);
        return ApiResult.ok();
    }

    private CustodyTransferView toTransferView(CustodyTransfer t) {
        return new CustodyTransferView(t.getId(), t.getAssetId(), t.getAssetType(), t.getFromUserId(),
                t.getToUserId(), t.getTransferType() == null ? null : t.getTransferType().name(),
                t.getStationId(), t.getSwapOrderId(), t.getChainHash(), t.getAssetSoh(), t.getAssetSoc(),
                t.getAssetCycleCount(), t.getTransferredAt());
    }

    private CustodyAuditView toAuditView(CustodyTransferAudit a) {
        return new CustodyAuditView(a.getId(), a.getTransferId(), a.getAssetId(), a.getAnomalyType(),
                a.getRiskScore(), a.getDescription(), a.getUserDailyTransferCount(),
                a.getAssetDailyTransferCount(), a.getDetectedAt(), a.getReviewed());
    }

    private CustodyDisputeView toDisputeView(CustodyDispute d) {
        return new CustodyDisputeView(d.getId(), d.getTransferId(), d.getAssetId(), d.getClaimantId(),
                d.getRespondentId(), d.getDisputeType(), d.getDescription(), d.getEvidenceUrls(),
                d.getClaimAmount(), d.getAwardedAmount(), d.getStatus(), d.getArbitratorId(),
                d.getResolution(), d.getResolvedAt(), d.getCreatedAt());
    }

    public record CustodyTransferReq(Long assetId, String assetType, Long fromUserId, Long toUserId,
                                     String transferType, Long stationId, Long swapOrderId,
                                     BigDecimal assetSoh, BigDecimal assetSoc, Integer assetCycleCount) {
    }
}
