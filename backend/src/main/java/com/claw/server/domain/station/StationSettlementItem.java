package com.claw.server.domain.station;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 结算明细（模块四 · 结算层）。
 *
 * <p>按 item_type 拆分：LOGISTICS（物流费）/ COMMISSION（服务站提成）/ RECOVERY（厂家净额回流）
 * / ADJUST（调整）。{@code refId} 仅存 ID（指向 movements / alloc / recovery_order），
 * 结算层不反向写这些表（BC-2）。
 */
@Entity
@Table(name = "station_settlement_items", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationSettlementItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long settlementId;

    /** LOGISTICS / COMMISSION / RECOVERY / ADJUST。 */
    @Column(nullable = false)
    private String itemType;

    /** 仅 ID 引用（movements / alloc / recovery_order），可空。 */
    private Long refId;

    private String description;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal amount = BigDecimal.ZERO;

    /** DEBIT（厂家出）/ CREDIT（服务站入）。 */
    @Column(nullable = false)
    private String direction;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
