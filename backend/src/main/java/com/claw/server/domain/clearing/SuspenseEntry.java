package com.claw.server.domain.clearing;

import com.claw.server.common.enums.SuspenseStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 差错挂账工单（对应 claw.suspense_entry，V131）。
 *
 * <p>长款/短款/未匹配/金额不符/汇兑差异的专门科目与工单，关联
 * {@code reconciliation_runs.id}，形成「差异 → 工单 → 处置」闭环（设计 §8）。
 * {@link #amount} 正=长款、负=短款。
 */
@Entity
@Table(name = "suspense_entry", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuspenseEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 工单号（唯一）。 */
    @Column(name = "entry_no", nullable = false, unique = true, length = 48)
    private String entryNo;

    /** 场景（可空）。 */
    @Column(name = "scene", length = 24)
    private String scene;

    /** 差异类型：CHANNEL_EXTRA/BOOK_EXTRA/AMOUNT_MISMATCH/FX_DIFF/UNMATCHED。 */
    @Column(name = "diff_type", nullable = false, length = 24)
    private String diffType;

    /** 通道回执/流水号。 */
    @Column(name = "channel_ref", length = 80)
    private String channelRef;

    /** 账本 bizRef。 */
    @Column(name = "ledger_ref", length = 64)
    private String ledgerRef;

    /** 金额：正=长款，负=短款。 */
    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    /** 关联 reconciliation_runs.id。 */
    @Column(name = "recon_run_id")
    private Long reconRunId;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 16)
    private SuspenseStatus status = SuspenseStatus.OPEN;

    /** 处置说明。 */
    @Column(name = "resolution", length = 512)
    private String resolution;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Builder.Default
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
