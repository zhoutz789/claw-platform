package com.claw.server.domain.advertising;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdPlayLogRepository extends JpaRepository<AdPlayLog, Long> {

    /** 获取某计划下的全部播放日志（用于结算）。 */
    List<AdPlayLog> findByCampaignId(Long campaignId);
}
