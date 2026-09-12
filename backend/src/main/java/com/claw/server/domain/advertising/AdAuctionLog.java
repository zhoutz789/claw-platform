package com.claw.server.domain.advertising;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 竞价日志（RTB 实时竞价增强期使用，本期仅预留）。
 * 对应 claw.ad_auction_log（V120）。
 */
@Entity
@Table(name = "ad_auction_log", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdAuctionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screen_id", nullable = false)
    private Long screenId;

    @Column(name = "round_at", nullable = false)
    @Builder.Default
    private Instant roundAt = Instant.now();

    @Column(name = "winner_campaign_id")
    private Long winnerCampaignId;

    private BigDecimal bid;

    @Column(name = "runner_up")
    private Long runnerUp;
}
