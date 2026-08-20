package com.claw.server.domain.compliance;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ComplianceRequests;
import com.claw.server.common.dto.ComplianceViews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 负责任信贷合规：DTI（负债收入比）≤50% 强制校验。
 * 超限直接 REJECT 拦截，记录留痕（银行流水/收入证明为人工复核依据）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DtiCheckService {

    /** DTI 红线：50%。 */
    public static final BigDecimal DTI_LIMIT = new BigDecimal("0.50");

    private final ComplianceCheckRepository checkRepository;

    @Transactional
    public ComplianceViews.DtiCheckView check(ComplianceRequests.DtiCheck req) {
        if (req.monthlyIncome().compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.invalidParam("error.compliance.income.invalid");
        }
        BigDecimal dti = req.monthlyDebt().divide(req.monthlyIncome(), 4, RoundingMode.HALF_UP);
        String result = dti.compareTo(DTI_LIMIT) <= 0 ? "PASS" : "REJECT";
        ComplianceCheck saved = checkRepository.save(ComplianceCheck.builder()
                .userId(req.userId())
                .monthlyDebt(req.monthlyDebt())
                .monthlyIncome(req.monthlyIncome())
                .dtiRate(dti)
                .result(result)
                .evidence(req.evidence())
                .build());
        log.info("DTI 校验 userId={} dti={} result={}", req.userId(), dti, result);
        return toView(saved);
    }

    private ComplianceViews.DtiCheckView toView(ComplianceCheck c) {
        return new ComplianceViews.DtiCheckView(c.getId(), c.getUserId(),
                c.getMonthlyDebt(), c.getMonthlyIncome(), c.getDtiRate(), c.getResult(), c.getCreatedAt());
    }
}
