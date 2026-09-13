package com.claw.server.domain.clearing;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 结算批次明细（对应 claw.settlement_batch_item，V130）。
 *
 * <p>批次 1 : 0..* 明细；每条明细可关联一条 {@link ClearingInstruction} 与收款方虚拟子户。
 */
@Entity
@Table(name = "settlement_batch_item", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettlementBatchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 所属批次。 */
    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** 关联清分指令（可空）。 */
    @Column(name = "clearing_instruction_id")
    private Long clearingInstructionId;

    /** 收款方虚拟子户（可空）。 */
    @Column(name = "payee_vsa_id")
    private Long payeeVsaId;

    /** 账本收款账户 id（可空）。 */
    @Column(name = "payee_account_id")
    private Long payeeAccountId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    /** 明细状态：PENDING / SENT / SETTLED / FAILED。 */
    @Builder.Default
    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
