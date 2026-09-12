package com.claw.server.domain.advertising;

import com.claw.server.common.enums.AdBidMode;
import com.claw.server.common.enums.AdCampaignStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 广告计划（时段/区域/报价/预算 + 计费模式）。计费走平台既有结算引擎，不另造。
 * 对应 claw.ad_campaigns（V120）。
 */
@Entity
@Table(name = "ad_campaigns", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private String name;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdCampaignStatus status = AdCampaignStatus.DRAFT;

    @Builder.Default
    @Column(nullable = false)
    private BigDecimal budget = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "bid_mode", nullable = false)
    private AdBidMode bidMode;

    @Builder.Default
    @Column(name = "bid_price", nullable = false)
    private BigDecimal bidPrice = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String timeSlots;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String regions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "targeting_json", columnDefinition = "jsonb")
    private String targetingJson;

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    @Builder.Default
    @Column(nullable = false)
    private BigDecimal spent = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
