package com.claw.server.domain.clearing;

import com.claw.server.common.enums.ClearingMode;
import com.claw.server.common.enums.ClearingScene;
import com.claw.server.common.enums.ClearingStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 清分指令单（对应 claw.clearing_instruction，V129）。
 *
 * <p>每条分账腿生成一条指令，承载通道动作与状态机（设计 §6.1）。
 * 三重幂等：{@code instructionNo}(UNIQUE) + {@code idemKey}(UNIQUE) + 账本 {@code bizType+bizRef}。
 */
@Entity
@Table(name = "clearing_instruction", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClearingInstruction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 指令号（幂等键，唯一）。 */
    @Column(name = "instruction_no", nullable = false, unique = true, length = 48)
    private String instructionNo;

    /** 业务幂等键（scene + basis_ref + payee）。 */
    @Column(name = "idem_key", nullable = false, length = 64)
    private String idemKey;

    /** 清分场景：R1..R12。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "scene", nullable = false, length = 24)
    private ClearingScene scene;

    /** 清分时机模式：AT_SOURCE / ON_ARRIVAL / BATCH。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private ClearingMode mode;

    /** 付款方虚拟子户（可空）。 */
    @Column(name = "payer_vsa_id")
    private Long payerVsaId;

    /** 收款方虚拟子户（可空）。 */
    @Column(name = "payee_vsa_id")
    private Long payeeVsaId;

    /** 账本收款账户 id（ledger.accounts.id）。 */
    @Column(name = "payee_account_id")
    private Long payeeAccountId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    /** 依据单号（订单/结算批次）。 */
    @Column(name = "basis_ref", length = 64)
    private String basisRef;

    /** 账本业务类型（冗余留痕）。 */
    @Column(name = "ledger_biz_type", length = 32)
    private String ledgerBizType;

    /** 账本业务单号（冗余留痕）。 */
    @Column(name = "ledger_biz_ref", length = 64)
    private String ledgerBizRef;

    /** 通道：ABA_PAYWAY / BAKONG。 */
    @Column(name = "channel", length = 32)
    private String channel;

    /** 状态机（设计 §6.1）。 */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 16)
    private ClearingStatus status = ClearingStatus.CREATED;

    /** 通道回执号 —— 待通道确认。 */
    @Column(name = "institution_ref", length = 80)
    private String institutionRef;

    @Builder.Default
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "fail_reason", length = 512)
    private String failReason;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "acked_at")
    private Instant ackedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Builder.Default
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Builder.Default
    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
