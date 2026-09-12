package com.claw.server.domain.advertising;

import com.claw.server.common.enums.AdBidMode;
import com.claw.server.common.enums.AdCampaignStatus;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdCampaignServiceTest {

    @Mock
    private AdCampaignRepository campaignRepository;

    @Mock
    private AdAccountRepository accountRepository;

    @InjectMocks
    private AdCampaignService service;

    @Test
    void createCampaign_persistsDraftWithDefaults() {
        when(accountRepository.existsById(1L)).thenReturn(true);
        when(campaignRepository.save(any(AdCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        AdCampaign c = service.createCampaign(1L, "Promo", AdBidMode.CPM,
                new BigDecimal("1.50"), new BigDecimal("100"));

        assertEquals(AdCampaignStatus.DRAFT, c.getStatus());
        assertEquals(1L, c.getAccountId());
        assertEquals(AdBidMode.CPM, c.getBidMode());
        verify(campaignRepository).save(c);
    }

    @Test
    void createCampaign_throwsWhenAccountMissing() {
        when(accountRepository.existsById(99L)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createCampaign(99L, "x", AdBidMode.CPM, BigDecimal.ONE, BigDecimal.ONE));
        assertTrue(ex.getMessage().contains("ad.account.not.found"));
        verify(campaignRepository, never()).save(any());
    }

    @Test
    void activate_flipsStatusToActive() {
        AdCampaign draft = AdCampaign.builder().id(5L).accountId(1L).name("P")
                .bidMode(AdBidMode.CPM).status(AdCampaignStatus.DRAFT).build();
        when(campaignRepository.findById(5L)).thenReturn(Optional.of(draft));
        when(campaignRepository.save(any(AdCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        AdCampaign active = service.activate(5L);
        assertEquals(AdCampaignStatus.ACTIVE, active.getStatus());
    }

    @Test
    void listByAccount_filtersByOwner() {
        AdCampaign a = AdCampaign.builder().id(1L).accountId(1L).name("A").bidMode(AdBidMode.CPM).build();
        AdCampaign b = AdCampaign.builder().id(2L).accountId(2L).name("B").bidMode(AdBidMode.CPM).build();
        when(campaignRepository.findAll()).thenReturn(List.of(a, b));

        assertEquals(1, service.listByAccount(1L).size());
    }
}
