package com.claw.server.domain.advertising;

import com.claw.server.common.enums.AdCampaignStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdMatchServiceTest {

    @Mock
    private AdCampaignRepository campaignRepository;

    @Mock
    private AdCampaignCreativeRepository campaignCreativeRepository;

    @Mock
    private AdMatchQueueRepository matchQueueRepository;

    @InjectMocks
    private AdMatchService service;

    private AdCampaign campaign(long id, AdCampaignStatus status, BigDecimal spent, BigDecimal budget) {
        return AdCampaign.builder().id(id).status(status).spent(spent).budget(budget).build();
    }

    @Test
    void match_returnsPinnedFirstThenByWeight() {
        AdCampaign pinned = campaign(2L, AdCampaignStatus.ACTIVE, BigDecimal.ZERO, new BigDecimal("100"));
        AdCampaign heavy = campaign(1L, AdCampaignStatus.ACTIVE, BigDecimal.ZERO, new BigDecimal("100"));
        when(campaignRepository.findAll()).thenReturn(List.of(heavy, pinned));

        // 计划 2 存在手动置顶队列行 → 视为 pinned
        when(matchQueueRepository.findByScreenIdAndCampaignId(100L, 2L))
                .thenReturn(List.of(AdMatchQueue.builder().id(9L).pinned(true).build()));
        when(matchQueueRepository.findByScreenIdAndCampaignId(100L, 1L))
                .thenReturn(List.of());

        // 计划 1 的素材权重更高
        when(campaignCreativeRepository.findByCampaignId(1L))
                .thenReturn(List.of(AdCampaignCreative.builder().id(1L).campaignId(1L).creativeId(1L).weight(10).build()));
        when(campaignCreativeRepository.findByCampaignId(2L))
                .thenReturn(List.of(AdCampaignCreative.builder().id(2L).campaignId(2L).creativeId(2L).weight(5).build()));

        when(matchQueueRepository.save(any(AdMatchQueue.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Long> ids = service.match(100L, new AdContext(100L, "scene", "tags", "aud", "slot"));

        assertEquals(List.of(2L, 1L), ids);

        ArgumentCaptor<AdMatchQueue> cap = ArgumentCaptor.forClass(AdMatchQueue.class);
        verify(matchQueueRepository, times(2)).save(cap.capture());
        List<AdMatchQueue> saved = cap.getAllValues();
        assertEquals(2L, saved.get(0).getCampaignId());
        assertTrue(saved.get(0).getPinned());
        assertEquals(1L, saved.get(1).getCampaignId());
        assertFalse(saved.get(1).getPinned());
    }

    @Test
    void match_excludesOverBudgetCampaign() {
        AdCampaign over = campaign(3L, AdCampaignStatus.ACTIVE, new BigDecimal("100"), new BigDecimal("100"));
        AdCampaign ok = campaign(4L, AdCampaignStatus.ACTIVE, BigDecimal.ZERO, new BigDecimal("100"));
        when(campaignRepository.findAll()).thenReturn(List.of(over, ok));
        when(matchQueueRepository.findByScreenIdAndCampaignId(anyLong(), anyLong())).thenReturn(List.of());
        when(campaignCreativeRepository.findByCampaignId(4L))
                .thenReturn(List.of(AdCampaignCreative.builder().id(4L).campaignId(4L).creativeId(4L).weight(1).build()));
        when(matchQueueRepository.save(any(AdMatchQueue.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Long> ids = service.match(100L, new AdContext(100L, "s", "t", "a", "sl"));

        assertEquals(List.of(4L), ids);
    }

    @Test
    void match_excludesNonActive() {
        AdCampaign paused = campaign(5L, AdCampaignStatus.PAUSED, BigDecimal.ZERO, new BigDecimal("100"));
        when(campaignRepository.findAll()).thenReturn(List.of(paused));

        List<Long> ids = service.match(100L, new AdContext(100L, "s", "t", "a", "sl"));
        assertTrue(ids.isEmpty());
        verify(matchQueueRepository, never()).save(any());
    }
}
