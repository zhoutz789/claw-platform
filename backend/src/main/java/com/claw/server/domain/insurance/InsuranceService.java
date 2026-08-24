package com.claw.server.domain.insurance;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.ClaimStatus;
import com.claw.server.common.enums.InsuranceStatus;
import com.claw.server.common.enums.InsuranceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 保险服务（V15, Phase 2）。
 *
 * <p>职责：
 * <ul>
 *   <li>投保 / 续保管理</li>
 *   <li>理赔流程（报案 → 审核 → 定损 → 赔付）</li>
 *   <li>保费逾期检测</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InsuranceService {

    private final VehicleInsuranceRepository insuranceRepository;
    private final AccidentClaimRepository claimRepository;

    /**
     * 投保：为资产创建保险单。
     */
    @Transactional
    public VehicleInsurance createPolicy(Long assetId, Long ownerUserId, Long templateId,
                                        InsuranceType type, String provider,
                                        BigDecimal coverage, BigDecimal deductible,
                                        BigDecimal monthlyPremium) {
        String policyNo = "INS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        VehicleInsurance insurance = VehicleInsurance.builder()
                .policyNo(policyNo)
                .assetId(assetId)
                .ownerUserId(ownerUserId)
                .templateId(templateId)
                .insuranceType(type)
                .provider(provider)
                .coverageAmount(coverage)
                .deductible(deductible)
                .premiumMonthly(monthlyPremium)
                .startDate(LocalDate.now())
                .premiumPaidThrough(LocalDate.now().plusMonths(1))
                .status(InsuranceStatus.ACTIVE)
                .build();

        insurance = insuranceRepository.save(insurance);
        log.info("投保成功 policyNo={} assetId={} type={}", policyNo, assetId, type);
        return insurance;
    }

    /**
     * 报案：创建理赔申请。
     */
    @Transactional
    public AccidentClaim fileClaim(Long insuranceId, Long assetId, Long claimantUserId,
                                   com.claw.server.common.enums.ClaimType claimType,
                                   Instant accidentDate, String location, String description,
                                   BigDecimal damageAmount) {
        VehicleInsurance insurance = insuranceRepository.findById(insuranceId)
                .orElseThrow(() -> BizException.notFound("error.insurance.not.found"));

        if (insurance.getStatus() != InsuranceStatus.ACTIVE) {
            throw BizException.of(40980, "error.insurance.inactive");
        }

        String claimNo = "CLM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        AccidentClaim claim = AccidentClaim.builder()
                .claimNo(claimNo)
                .insuranceId(insuranceId)
                .assetId(assetId)
                .claimantUserId(claimantUserId)
                .claimType(claimType)
                .accidentDate(accidentDate)
                .accidentLocation(location)
                .description(description)
                .damageAmount(damageAmount)
                .status(ClaimStatus.FILED)
                .filedAt(Instant.now())
                .build();

        claim = claimRepository.save(claim);
        log.info("理赔报案 claimNo={} assetId={} type={}", claimNo, assetId, claimType);
        return claim;
    }

    /**
     * 审核定损。
     */
    @Transactional
    public AccidentClaim assessClaim(Long claimId, Long reviewedBy,
                                     BigDecimal assessedAmount, String notes) {
        AccidentClaim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> BizException.notFound("error.claim.not.found"));

        if (claim.getStatus() != ClaimStatus.UNDER_REVIEW) {
            claim.setStatus(ClaimStatus.UNDER_REVIEW);
        }

        claim.setAssessedAmount(assessedAmount);
        claim.setDeductibleApplied(assessedAmount.min(
                claim.getInsuranceId() != null ? BigDecimal.ZERO : BigDecimal.ZERO)); // 简化
        claim.setPayoutAmount(assessedAmount.subtract(claim.getDeductibleApplied()));
        claim.setStatus(ClaimStatus.ASSESSED);
        claim.setReviewedBy(reviewedBy);
        claim.setReviewedAt(Instant.now());
        claim.setReviewNotes(notes);
        claim.setUpdatedAt(Instant.now());
        claim = claimRepository.save(claim);

        log.info("理赔定损 claimId={} assessed={} payout={}", claimId, assessedAmount, claim.getPayoutAmount());
        return claim;
    }

    /**
     * 批准赔付。
     */
    @Transactional
    public AccidentClaim approveClaim(Long claimId, String ledgerTxnId) {
        AccidentClaim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> BizException.notFound("error.claim.not.found"));

        if (claim.getStatus() != ClaimStatus.ASSESSED) {
            throw BizException.of(40982, "error.claim.not.assessed");
        }

        claim.setLedgerTxnId(ledgerTxnId);
        claim.setStatus(ClaimStatus.PAID);
        claim.setResolvedAt(Instant.now());
        claim.setUpdatedAt(Instant.now());
        claim = claimRepository.save(claim);

        log.info("理赔赔付 claimId={} payout={}", claimId, claim.getPayoutAmount());
        return claim;
    }

    /**
     * 拒赔。
     */
    @Transactional
    public AccidentClaim rejectClaim(Long claimId, Long reviewedBy, String reason) {
        AccidentClaim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> BizException.notFound("error.claim.not.found"));

        claim.setStatus(ClaimStatus.REJECTED);
        claim.setReviewedBy(reviewedBy);
        claim.setReviewedAt(Instant.now());
        claim.setReviewNotes(reason);
        claim.setResolvedAt(Instant.now());
        claim.setUpdatedAt(Instant.now());
        claim = claimRepository.save(claim);

        log.info("理赔拒赔 claimId={} reason={}", claimId, reason);
        return claim;
    }

    /**
     * 查询资产有效保险。
     */
    @Transactional(readOnly = true)
    public VehicleInsurance getActiveInsurance(Long assetId) {
        return insuranceRepository.findActiveByAsset(assetId).orElse(null);
    }

    /**
     * 查询待处理理赔。
     */
    @Transactional(readOnly = true)
    public List<AccidentClaim> listPendingClaims() {
        return claimRepository.findByStatusAndDeletedFalse(ClaimStatus.FILED);
    }
}
