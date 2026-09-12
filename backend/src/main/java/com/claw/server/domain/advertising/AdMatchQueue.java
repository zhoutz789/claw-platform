package com.claw.server.domain.advertising;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 每屏的匹配排队（手动置顶 + 系统排名）。
 * 对应 claw.ad_match_queue（V120）。
 */
@Entity
@Table(name = "ad_match_queue", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdMatchQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screen_id", nullable = false)
    private Long screenId;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Builder.Default
    @Column(nullable = false)
    private Integer rank = 0;

    @Builder.Default
    @Column(nullable = false)
    private Boolean pinned = false;

    @Column(name = "next_at")
    private Instant nextAt;
}
