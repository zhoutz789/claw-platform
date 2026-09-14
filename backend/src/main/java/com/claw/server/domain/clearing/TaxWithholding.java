package com.claw.server.domain.clearing;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * WHT 预扣税代扣台账（对应 claw.tax_withholding，V134）。
 *
 * <p>每笔对外付款按收款方税务档案代扣 WHT 后落一条记录，作为月度 WHT 申报底稿的数据源。
 * 与 {@code clearing_instruction} 一对一（一笔指令至多一条代扣记录；WHT=0 时不写本表）。
 *
 * <p>字段均为小写结尾命名（无大写结尾），不触发 ArchUnit 实体列名守护；仍显式声明 {@code @Column}
 * 以保持与 Flyway DDL 列名一致。
 */
@Entity
@Table(name = "tax_withholding", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxWithholding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 关联清分指令（claw.clearing_instruction.id）。 */
    @Column(name = "clearing_instruction_id")
    private Long clearingInstructionId;

    /** 收款方虚拟子户（claw.virtual_subaccount.id）。 */
    @Column(name = "payee_vsa_id")
    private Long payeeVsaId;

    /** 代扣前毛额（= 该腿分账金额）。 */
    @Column(name = "gross_amount", nullable = false)
    private BigDecimal grossAmount;

    /** 适用 WHT 税率（0~1，如 0.10）。 */
    @Builder.Default
    @Column(name = "wht_rate", nullable = false)
    private BigDecimal whtRate = BigDecimal.ZERO;

    /** 代扣税额。 */
    @Builder.Default
    @Column(name = "wht_amount", nullable = false)
    private BigDecimal whtAmount = BigDecimal.ZERO;

    /** 代扣后净额（= 实际支付收款方金额）。 */
    @Column(name = "net_amount", nullable = false)
    private BigDecimal netAmount;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    /** 申报期 YYYY-MM。 */
    @Column(name = "tax_period", nullable = false, length = 7)
    private String taxPeriod;

    /** 缴纳状态：UNPAID / PAID。 */
    @Builder.Default
    @Column(name = "paid_status", nullable = false, length = 16)
    private String paidStatus = "UNPAID";

    /** 实际缴纳时间（代缴后回填）。 */
    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
