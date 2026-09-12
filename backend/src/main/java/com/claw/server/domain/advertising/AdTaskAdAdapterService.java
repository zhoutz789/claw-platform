package com.claw.server.domain.advertising;

import com.claw.server.common.enums.AdBidMode;
import com.claw.server.common.enums.AdCampaignStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * TaskAd 适配器服务（A7）：将任务域的 {@link TaskAdInput} 适配为广告子系统的
 * 计划 + 素材 + 计划-素材关联 + 匹配队列行，返回新建计划 id。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdTaskAdAdapterService {

    private final AdCampaignRepository adCampaignRepository;
    private final AdCreativeRepository adCreativeRepository;
    private final AdCampaignCreativeRepository adCampaignCreativeRepository;
    private final AdMatchQueueRepository adMatchQueueRepository;

    @Transactional
    public Long adapt(TaskAdInput in) {
        AdCampaign campaign = AdCampaign.builder()
                .name("TaskAd-" + in.id())
                .status(AdCampaignStatus.DRAFT)
                .budget(BigDecimal.ZERO)
                .bidMode(AdBidMode.CPM)
                .bidPrice(BigDecimal.ZERO)
                .build();
        AdCampaign savedCampaign = adCampaignRepository.save(campaign);

        AdCreative creative = AdCreative.builder()
                .source("TASKAD")
                .fileUrl(in.mediaUrl())
                .mime(in.mime())
                .durationSec(in.displayDurationSeconds())
                .build();
        AdCreative savedCreative = adCreativeRepository.save(creative);

        AdCampaignCreative cc = AdCampaignCreative.builder()
                .campaignId(savedCampaign.getId())
                .creativeId(savedCreative.getId())
                .weight(1)
                .build();
        adCampaignCreativeRepository.save(cc);

        AdMatchQueue queue = AdMatchQueue.builder()
                .screenId(null)
                .campaignId(savedCampaign.getId())
                .rank(0)
                .pinned(false)
                .nextAt(Instant.now())
                .build();
        adMatchQueueRepository.save(queue);

        log.info("adapted TaskAd id={} -> campaign={}", in.id(), savedCampaign.getId());
        return savedCampaign.getId();
    }
}
