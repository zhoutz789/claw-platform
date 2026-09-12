package com.claw.server.domain.advertising;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdBillingServiceTest {

    @Mock
    private AdPlayLogRepository playLogRepository;

    @Mock
    private AdCampaignRepository campaignRepository;

    @InjectMocks
    private AdBillingService service;

    @Test
    void recordPlay_savesUnsettledAndDecrementsSpent() {
        AdCampaign campaign = AdCampaign.builder().id(1L).spent(new BigDecimal("50")).build();
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign));
        when(playLogRepository.save(any(AdPlayLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(campaignRepository.save(any(AdCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        AdPlayLog log = service.recordPlay(1L, 2L, 3L, 15000L, 2, 5, "CPM", new BigDecimal("10"));

        assertFalse(log.getSettled());
        assertEquals(5, log.getClickCount());
        assertEquals(15000L, log.getDurationMs());
        // spent 50 - 10 = 40
        assertEquals(new BigDecimal("40"), campaign.getSpent());
        verify(playLogRepository).save(any(AdPlayLog.class));
        verify(campaignRepository).save(campaign);
    }

    @Test
    void recordPlay_clampsSpentAtZero() {
        AdCampaign campaign = AdCampaign.builder().id(1L).spent(new BigDecimal("5")).build();
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign));
        when(playLogRepository.save(any(AdPlayLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(campaignRepository.save(any(AdCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recordPlay(1L, 2L, 3L, 1000L, 1, 0, "CPM", new BigDecimal("99"));

        assertEquals(BigDecimal.ZERO, campaign.getSpent());
    }

    @Test
    void settleCampaign_marksAllLogsSettled() {
        AdPlayLog l1 = AdPlayLog.builder().id(1L).campaignId(1L).settled(false).build();
        AdPlayLog l2 = AdPlayLog.builder().id(2L).campaignId(1L).settled(false).build();
        when(playLogRepository.findByCampaignId(1L)).thenReturn(List.of(l1, l2));
        when(playLogRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.settleCampaign(1L);

        assertTrue(l1.getSettled());
        assertTrue(l2.getSettled());
        verify(playLogRepository).saveAll(List.of(l1, l2));
    }
}
