package com.claw.server.domain.funds;

import com.claw.server.common.enums.LocationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 托管点位（对应 claw.funds_location，V128）。
 *
 * <p>显式回答「每笔钱在哪家机构的哪个账户」，并把破产隔离在
 * {@link LocationType} 上显式建模（平台自有 / 客户存管 / 商家直连）。
 * {@code coversAccountTypes} 为逗号分隔的账本科目名（审计用，见
 * {@link FundsLocationService#coveredAccountTypes(Long)}）。
 */
@Entity
@Table(name = "funds_location", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundsLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 点位编码（唯一）：ABA_PAYWAY_RESERVE / BANK_ESCROW_01 / MPTC_DIRECT。 */
    @Column(name = "location_code", nullable = false, unique = true, length = 48)
    private String locationCode;

    /** 托管机构：ABA / BAKONG / BANK_X。 */
    @Column(name = "institution", nullable = false, length = 64)
    private String institution;

    /** 机构账户号（脱敏存，建议仅后 4 位 + 指纹）。 */
    @Column(name = "institution_acct_no", length = 64)
    private String institutionAcctNo;

    /** 点位类型（破产隔离维度）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false, length = 24)
    private LocationType locationType;

    /** 通道：ABA_PAYWAY / BAKONG / BANK。 */
    @Column(name = "channel", length = 32)
    private String channel;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    /** 覆盖账本科目（逗号分隔，审计用）。 */
    @Column(name = "covers_account_types", length = 256)
    private String coversAccountTypes;

    @Builder.Default
    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    /** 最近一次对账时间（L3 托管对账锚点）。 */
    @Column(name = "last_reconciled_at")
    private Instant lastReconciledAt;

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
