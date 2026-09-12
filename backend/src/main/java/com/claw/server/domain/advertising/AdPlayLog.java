package com.claw.server.domain.advertising;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 广告播放计费源：每次播放/每时段写一行，进既有结算引擎。
 * 对应 claw.ad_play_logs（V120）。
 */
@Entity
@Table(name = "ad_play_logs", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdPlayLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "creative_id", nullable = false)
    private Long creativeId;

    @Column(name = "screen_id", nullable = false)
    private Long screenId;

    @Column(name = "played_at", nullable = false)
    @Builder.Default
    private Instant playedAt = Instant.now();

    @Column(name = "duration_ms")
    private Long durationMs;

    @Builder.Default
    @Column(name = "play_count", nullable = false)
    private Integer playCount = 1;

    @Builder.Default
    @Column(name = "click_count", nullable = false)
    private Integer clickCount = 0;

    @Column(name = "charge_mode")
    private String chargeMode;

    @Builder.Default
    @Column(nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false)
    private Boolean settled = false;
}
