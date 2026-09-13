package com.claw.server.domain.clearing;

import com.claw.server.common.enums.BatchStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 结算批次（对应 claw.settlement_batch，V130）。
 *
 * <p>周期汇总 → 审核 → 下发 → 回执 → 对账闭环（设计 §6.2）；
 * {@link #dueDate} 承载 T+N（货款类 T+7）。
 */
@Entity
@Table(name = "settlement_batch", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettlementBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 批次号（唯一）。 */
    @Column(name = "batch_no", nullable = false, unique = true, length = 48)
    private String batchNo;

    @Column(name = "biz_scene", nullable = false, length = 24)
    private String bizScene;

    /** 结算周期：T+1 / T+7。 */
    @Column(name = "cycle", nullable = false, length = 8)
    private String cycle;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    /** 应付日 = period_end + N（货款类 T+7）。 */
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Builder.Default
    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "item_count", nullable = false)
    private Integer itemCount = 0;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 16)
    private BatchStatus status = BatchStatus.COLLECTING;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "fail_reason", length = 512)
    private String failReason;

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
