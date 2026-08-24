package com.claw.server.domain.custody;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.TransferType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 管理权转移服务（V14, Phase 1）。
 *
 * <p>职责：
 * <ul>
 *   <li>记录每次资产管理权转移（换电/租赁/回收/入池/出池）</li>
 *   <li>产权链查询（完整历史追踪）</li>
 *   <li>争议仲裁管理</li>
 *   <li>异常审计查询</li>
 * </ul>
 *
 * <p>对应 PRD 4.17 + D45 不可篡改产权链。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustodyService {

    private final CustodyTransferRepository transferRepository;
    private final CustodyDisputeRepository disputeRepository;
    private final CustodyTransferAuditRepository auditRepository;

    /**
     * 记录管理权转移（DB 触发器自动计算 chain_hash）。
     */
    @Transactional
    public CustodyTransfer recordTransfer(Long assetId, String assetType,
                                          Long fromUserId, Long toUserId,
                                          TransferType transferType,
                                          Long stationId, Long swapOrderId,
                                          BigDecimal soh, BigDecimal soc, Integer cycleCount) {
        // 获取前序转移（资产最近一次转移）
        CustodyTransfer prevTransfer = transferRepository
                .findFirstByAssetIdAndDeletedFalseOrderByTransferredAtDesc(assetId);
        Long prevId = prevTransfer != null ? prevTransfer.getId() : null;

        CustodyTransfer transfer = CustodyTransfer.builder()
                .assetId(assetId)
                .assetType(assetType)
                .fromUserId(fromUserId)
                .toUserId(toUserId)
                .transferType(transferType)
                .stationId(stationId)
                .swapOrderId(swapOrderId)
                .prevTransferId(prevId)
                .chainHash("PENDING") // DB 触发器自动计算
                .assetSoh(soh)
                .assetSoc(soc)
                .assetCycleCount(cycleCount)
                .transferredAt(Instant.now())
                .build();

        transfer = transferRepository.save(transfer);
        log.info("管理权转移 assetId={} {} -> {} type={}", assetId, fromUserId, toUserId, transferType);
        return transfer;
    }

    /**
     * 查询资产完整产权链。
     */
    @Transactional(readOnly = true)
    public List<CustodyTransfer> getAssetChain(Long assetId) {
        return transferRepository.findByAssetIdAndDeletedFalseOrderByTransferredAtDesc(assetId);
    }

    /**
     * 查询用户当前持有的资产。
     */
    @Transactional(readOnly = true)
    public List<CustodyTransfer> getUserHoldings(Long userId) {
        return transferRepository.findByToUserIdAndDeletedFalse(userId);
    }

    /**
     * 发起争议仲裁。
     */
    @Transactional
    public CustodyDispute fileDispute(Long transferId, Long assetId,
                                      Long claimantId, Long respondentId,
                                      String disputeType, String description, String evidenceUrls) {
        CustodyDispute dispute = CustodyDispute.builder()
                .transferId(transferId)
                .assetId(assetId)
                .claimantId(claimantId)
                .respondentId(respondentId)
                .disputeType(disputeType)
                .description(description)
                .evidenceUrls(evidenceUrls)
                .status("PENDING")
                .build();

        dispute = disputeRepository.save(dispute);
        log.info("发起争议仲裁 disputeId={} transfer={} type={}", dispute.getId(), transferId, disputeType);
        return dispute;
    }

    /**
     * 解决争议。
     */
    @Transactional
    public CustodyDispute resolveDispute(Long disputeId, Long arbitratorId,
                                         String resolution, String status) {
        CustodyDispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> BizException.notFound("error.dispute.not.found"));

        dispute.setArbitratorId(arbitratorId);
        dispute.setResolution(resolution);
        dispute.setStatus(status);
        dispute.setResolvedAt(Instant.now());
        dispute.setUpdatedAt(Instant.now());
        return disputeRepository.save(dispute);
    }

    /**
     * 查询资产异常审计记录。
     */
    @Transactional(readOnly = true)
    public List<CustodyTransferAudit> getAssetAnomalies(Long assetId) {
        return auditRepository.findByAssetIdAndDeletedFalse(assetId);
    }

    /**
     * 查询未审核的异常记录。
     */
    @Transactional(readOnly = true)
    public List<CustodyTransferAudit> getUnreviewedAnomalies() {
        return auditRepository.findByReviewedFalseAndDeletedFalse();
    }

    /**
     * 标记异常已审核。
     */
    @Transactional
    public void markAnomalyReviewed(Long auditId) {
        auditRepository.findById(auditId).ifPresent(audit -> {
            audit.setReviewed(true);
            auditRepository.save(audit);
        });
    }
}
