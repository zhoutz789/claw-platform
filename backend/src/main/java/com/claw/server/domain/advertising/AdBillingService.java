package com.claw.server.domain.advertising;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 播放计费服务（A5）：每次播放写一条 {@link AdPlayLog}，并回写计划的 spent（按金额扣减，不低于 0）；
 * 结算时将某计划全部播放日志标记为已结算。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdBillingService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final AdPlayLogRepository adPlayLogRepository;
    private final AdCampaignRepository adCampaignRepository;

    /**
     * 记录一次播放并扣减计划 spent。
     *
     * @return 持久化后的播放日志（settled=false）
     */
    @Transactional
    public AdPlayLog recordPlay(Long campaignId, Long creativeId, Long screenId, long durationMs,
                                int playCount, int clickCount, String chargeMode, BigDecimal amount) {
        AdPlayLog log = AdPlayLog.builder()
                .campaignId(campaignId)
                .creativeId(creativeId)
                .screenId(screenId)
                .playedAt(Instant.now())
                .durationMs(durationMs)
                .playCount(playCount)
                .clickCount(clickCount)
                .chargeMode(chargeMode)
                .amount(amount == null ? ZERO : amount)
                .settled(false)
                .build();
        AdPlayLog saved = adPlayLogRepository.save(log);

        AdCampaign campaign = adCampaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("ad.campaign.not.found:" + campaignId));
        BigDecimal spent = campaign.getSpent() == null ? ZERO : campaign.getSpent();
        BigDecimal newSpent = spent.subtract(amount == null ? ZERO : amount);
        if (newSpent.compareTo(ZERO) < 0) {
            newSpent = ZERO;
        }
        campaign.setSpent(newSpent);
        adCampaignRepository.save(campaign);
        return saved;
    }

    /**
     * 将某计划全部播放日志标记为已结算。
     */
    @Transactional
    public void settleCampaign(Long campaignId) {
        List<AdPlayLog> logs = adPlayLogRepository.findByCampaignId(campaignId);
        for (AdPlayLog log : logs) {
            log.setSettled(true);
        }
        adPlayLogRepository.saveAll(logs);
        log.info("settled {} play logs for campaign={}", logs.size(), campaignId);
    }
}
