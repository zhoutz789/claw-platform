package com.claw.server.domain.advertising;

import jakarta.persistence.*;
import lombok.*;

/**
 * 广告计划 ↔ 素材 多对多 + 权重。
 * 对应 claw.ad_campaign_creatives（V120）。
 */
@Entity
@Table(name = "ad_campaign_creatives", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdCampaignCreative {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "creative_id", nullable = false)
    private Long creativeId;

    @Builder.Default
    @Column(nullable = false)
    private Integer weight = 1;
}
