package com.claw.server.domain.operator;

import com.claw.server.common.enums.BondStatus;
import com.claw.server.common.enums.KycApprovalStatus;
import com.claw.server.common.enums.OperatorAccountType;
import com.claw.server.common.enums.OperatorAccountStatus;
import com.claw.server.common.enums.OperatorType;
import com.claw.server.common.enums.RiskSeverity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 站方资金服务（V10, Phase 1）。
 *
 * <p>职责：
 * <ul>
 *   <li>站方资金账户管理（管理收益/服务费/光伏收益/回收代办）</li>
 *   <li>站长保证金动态核定（R3: base + asset_value * rate）</li>
 *   <li>站长 KYC 审批（R1: CamDigiKey eKYC + 信用分 + 无犯罪记录）</li>
 *   <li>风控事件记录与自动熔断（R1: 实时监控）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperatorFinanceService {

    private final OperatorAccountRepository accountRepository;
    private final OperatorBondRepository bondRepository;
    private final OperatorKycRecordRepository kycRepository;
    private final OperatorRiskEventRepository riskEventRepository;

    // ------------------------------------------------------------------
    // 1. 站方资金账户
    // ------------------------------------------------------------------

    /**
     * 创建站方资金账户。
     */
    @Transactional
    public OperatorAccount createAccount(Long operatorId, Long stationId,
                                         OperatorAccountType type) {
        OperatorAccount account = OperatorAccount.builder()
                .operatorId(operatorId)
                .stationId(stationId)
                .accountType(type)
                .status(OperatorAccountStatus.ACTIVE)
                .build();

        account = accountRepository.save(account);
        log.info("创建站方账户 operatorId={} stationId={} type={}", operatorId, stationId, type);
        return account;
    }

    /**
     * 查询站方所有账户。
     */
    @Transactional(readOnly = true)
    public List<OperatorAccount> listAccounts(Long operatorId) {
        return accountRepository.findByOperatorIdAndDeletedFalse(operatorId);
    }

    // ------------------------------------------------------------------
    // 2. 站长保证金
    // ------------------------------------------------------------------

    /**
     * 创建/更新站长保证金（R3 动态核定）。
     */
    @Transactional
    public OperatorBond upsertBond(Long operatorId, Long stationId,
                                   OperatorType operatorType,
                                   BigDecimal baseBond, BigDecimal managedAssetValue,
                                   BigDecimal bondRate) {
        BigDecimal required = baseBond.add(
                managedAssetValue.multiply(bondRate != null ? bondRate : BigDecimal.valueOf(0.05)));

        OperatorBond bond = OperatorBond.builder()
                .operatorId(operatorId)
                .stationId(stationId)
                .operatorType(operatorType)
                .baseBond(baseBond)
                .managedAssetValue(managedAssetValue)
                .bondRate(bondRate != null ? bondRate : BigDecimal.valueOf(0.05))
                .requiredBond(required)
                .postedBond(BigDecimal.ZERO)
                .shortfall(required)
                .status(BondStatus.PENDING)
                .build();

        // DB 触发器会自动计算 required_bond 和 shortfall
        bond = bondRepository.save(bond);
        log.info("站长保证金创建 operatorId={} required={} shortfall={}",
                operatorId, required, bond.getShortfall());
        return bond;
    }

    /**
     * 缴纳保证金。
     */
    @Transactional
    public OperatorBond postBond(Long bondId, BigDecimal amount) {
        OperatorBond bond = bondRepository.findById(bondId).orElseThrow();

        bond.setPostedBond(bond.getPostedBond().add(amount));
        bond.setShortfall(bond.getRequiredBond().subtract(bond.getPostedBond()));
        if (bond.getShortfall().compareTo(BigDecimal.ZERO) <= 0) {
            bond.setStatus(BondStatus.SUFFICIENT);
            bond.setShortfall(BigDecimal.ZERO);
        }
        bond.setUpdatedAt(Instant.now());
        return bondRepository.save(bond);
    }

    // ------------------------------------------------------------------
    // 3. KYC 审批
    // ------------------------------------------------------------------

    /**
     * 审批 KYC（R1 准入门槛）。
     */
    @Transactional
    public OperatorKycRecord approveKyc(Long kycId, Long approvedBy) {
        OperatorKycRecord kyc = kycRepository.findById(kycId).orElseThrow();

        // 校验：须通过背景调查 + 无犯罪记录 + 信用分 >= 650
        if (!"PASS".equals(kyc.getBackgroundCheck())) {
            throw new IllegalArgumentException("KYC background check not passed");
        }
        if (Boolean.TRUE.equals(kyc.getCriminalRecord())) {
            throw new IllegalArgumentException("Criminal record found");
        }
        if (kyc.getClawScore() != null && kyc.getClawScore() < 650) {
            throw new IllegalArgumentException("Claw Score below threshold");
        }

        kyc.setStatus(KycApprovalStatus.APPROVED);
        kyc.setApprovedBy(approvedBy);
        kyc.setApprovedAt(Instant.now());
        kyc.setUpdatedAt(Instant.now());
        return kycRepository.save(kyc);
    }

    /**
     * 拒绝 KYC。
     */
    @Transactional
    public OperatorKycRecord rejectKyc(Long kycId, Long rejectedBy, String reason) {
        OperatorKycRecord kyc = kycRepository.findById(kycId).orElseThrow();

        kyc.setStatus(KycApprovalStatus.REJECTED);
        kyc.setApprovedBy(rejectedBy);
        kyc.setApprovedAt(Instant.now());
        kyc.setRejectReason(reason);
        kyc.setUpdatedAt(Instant.now());
        return kycRepository.save(kyc);
    }

    // ------------------------------------------------------------------
    // 4. 风控事件
    // ------------------------------------------------------------------

    /**
     * 记录风控事件（DB 触发器自动确定 auto_action）。
     */
    @Transactional
    public OperatorRiskEvent recordRiskEvent(Long operatorId, Long stationId,
                                             com.claw.server.common.enums.RiskEventType eventType,
                                             RiskSeverity severity,
                                             String description,
                                             BigDecimal detectedValue, BigDecimal expectedValue) {
        OperatorRiskEvent event = OperatorRiskEvent.builder()
                .operatorId(operatorId)
                .stationId(stationId)
                .eventType(eventType)
                .severity(severity)
                .description(description)
                .detectedValue(detectedValue)
                .expectedValue(expectedValue)
                .build();

        event = riskEventRepository.save(event);

        if (severity == RiskSeverity.CRITICAL) {
            log.warn("站长严重风控事件 operatorId={} type={} → 自动熔断", operatorId, eventType);
        }

        return event;
    }

    /**
     * 解决风控事件。
     */
    @Transactional
    public OperatorRiskEvent resolveEvent(Long eventId, Long resolvedBy, String note) {
        OperatorRiskEvent event = riskEventRepository.findById(eventId).orElseThrow();
        event.setResolved(true);
        event.setResolvedBy(resolvedBy);
        event.setResolvedAt(Instant.now());
        event.setResolutionNote(note);
        event.setUpdatedAt(Instant.now());
        return riskEventRepository.save(event);
    }

    /**
     * 查询未解决的风控事件。
     */
    @Transactional(readOnly = true)
    public List<OperatorRiskEvent> listUnresolved() {
        return riskEventRepository.findByResolvedFalseAndDeletedFalse();
    }
}
