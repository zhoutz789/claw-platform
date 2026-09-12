package com.claw.server.domain.advertising;

import com.claw.server.common.enums.AdBidMode;
import com.claw.server.common.enums.AdCampaignStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 广告计划服务（A1 薄层）：建计划、激活、按账户列示。
 * 计费/分账交由平台既有结算引擎，本服务只管计划生命周期。
 */
@Service
@RequiredArgsConstructor
public class AdCampaignService {

    private final AdCampaignRepository campaignRepository;
    private final AdAccountRepository accountRepository;

    @Transactional
    public AdCampaign createCampaign(Long accountId, String name, AdBidMode bidMode,
                                     BigDecimal bidPrice, BigDecimal budget) {
        if (!accountRepository.existsById(accountId)) {
            throw new IllegalArgumentException("ad.account.not.found:" + accountId);
        }
        AdCampaign campaign = AdCampaign.builder()
                .accountId(accountId)
                .name(name)
                .bidMode(bidMode)
                .bidPrice(bidPrice)
                .budget(budget)
                .status(AdCampaignStatus.DRAFT)
                .build();
        return campaignRepository.save(campaign);
    }

    @Transactional
    public AdCampaign activate(Long campaignId) {
        AdCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("ad.campaign.not.found:" + campaignId));
        campaign.setStatus(AdCampaignStatus.ACTIVE);
        return campaignRepository.save(campaign);
    }

    public List<AdCampaign> listByAccount(Long accountId) {
        return campaignRepository.findAll().stream()
                .filter(c -> accountId.equals(c.getAccountId()))
                .toList();
    }
}
