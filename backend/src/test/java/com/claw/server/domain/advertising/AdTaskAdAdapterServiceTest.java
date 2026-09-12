package com.claw.server.domain.advertising;

import com.claw.server.common.enums.AdBidMode;
import com.claw.server.common.enums.AdCampaignStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdTaskAdAdapterServiceTest {

    @Mock
    private AdCampaignRepository campaignRepository;

    @Mock
    private AdCreativeRepository creativeRepository;

    @Mock
    private AdCampaignCreativeRepository campaignCreativeRepository;

    @Mock
    private AdMatchQueueRepository matchQueueRepository;

    @InjectMocks
    private AdTaskAdAdapterService service;

    @Test
    void adapt_createsCampaignCreativeAndQueue() {
        when(campaignRepository.save(any(AdCampaign.class))).thenAnswer(inv -> {
            AdCampaign c = inv.getArgument(0);
            c.setId(11L);
            return c;
        });
        when(creativeRepository.save(any(AdCreative.class))).thenAnswer(inv -> {
            AdCreative c = inv.getArgument(0);
            c.setId(22L);
            return c;
        });
        when(campaignCreativeRepository.save(any(AdCampaignCreative.class))).thenAnswer(inv -> inv.getArgument(0));
        when(matchQueueRepository.save(any(AdMatchQueue.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskAdInput in = new TaskAdInput(5L, 1L, "http://x/a.png", "image/png", "BODY", 30, 9L);
        Long id = service.adapt(in);

        assertEquals(11L, id);

        ArgumentCaptor<AdCampaign> cc = ArgumentCaptor.forClass(AdCampaign.class);
        verify(campaignRepository).save(cc.capture());
        assertEquals("TaskAd-5", cc.getValue().getName());
        assertEquals(AdCampaignStatus.DRAFT, cc.getValue().getStatus());
        assertEquals(AdBidMode.CPM, cc.getValue().getBidMode());
        assertEquals(BigDecimal.ZERO, cc.getValue().getBudget());
        assertEquals(BigDecimal.ZERO, cc.getValue().getBidPrice());

        ArgumentCaptor<AdCreative> cr = ArgumentCaptor.forClass(AdCreative.class);
        verify(creativeRepository).save(cr.capture());
        assertEquals("TASKAD", cr.getValue().getSource());
        assertEquals("http://x/a.png", cr.getValue().getFileUrl());
        assertEquals("image/png", cr.getValue().getMime());
        assertEquals(Integer.valueOf(30), cr.getValue().getDurationSec());

        ArgumentCaptor<AdCampaignCreative> ccc = ArgumentCaptor.forClass(AdCampaignCreative.class);
        verify(campaignCreativeRepository).save(ccc.capture());
        assertEquals(11L, ccc.getValue().getCampaignId());
        assertEquals(22L, ccc.getValue().getCreativeId());
        assertEquals(Integer.valueOf(1), ccc.getValue().getWeight());

        ArgumentCaptor<AdMatchQueue> mq = ArgumentCaptor.forClass(AdMatchQueue.class);
        verify(matchQueueRepository).save(mq.capture());
        assertEquals(11L, mq.getValue().getCampaignId());
        assertEquals(Integer.valueOf(0), mq.getValue().getRank());
        assertFalse(mq.getValue().getPinned());
        assertNull(mq.getValue().getScreenId());
    }
}
