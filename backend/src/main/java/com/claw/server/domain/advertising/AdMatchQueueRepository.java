package com.claw.server.domain.advertising;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdMatchQueueRepository extends JpaRepository<AdMatchQueue, Long> {

    /** 获取某屏上某计划的匹配队列行（用于识别手动置顶）。 */
    List<AdMatchQueue> findByScreenIdAndCampaignId(Long screenId, Long campaignId);
}
