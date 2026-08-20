package com.claw.server.domain.compliance;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 合规检查记录（对应 claw.compliance_checks）。
 * 负责任信贷红线：DTI（月债务/月净收入）≤50% 强制校验，结果留痕供监管/审计。
 */
@Entity
@Table(name = "compliance_checks", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Builder.Default
    @Column(nullable = false, length = 32)
    private String checkType = "DTI_CHECK";

    @Builder.Default
    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal monthlyDebt = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal monthlyIncome = BigDecimal.ZERO;

    @Column(precision = 5, scale = 4)
    private BigDecimal dtiRate;

    @Column(nullable = false, length = 16)
    private String result;      // PASS | REJECT

    @Column(columnDefinition = "jsonb")
    private String evidence;    // 复核依据（银行流水/收入证明引用），JSON 字符串

    private Long checkedBy;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
