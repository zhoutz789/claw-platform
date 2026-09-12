package com.claw.server.domain.advertising;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdCampaignCreativeRepository extends JpaRepository<AdCampaignCreative, Long> {

    /** 获取某计划下的全部 计划-素材 关联。 */
    List<AdCampaignCreative> findByCampaignId(Long campaignId);
}
