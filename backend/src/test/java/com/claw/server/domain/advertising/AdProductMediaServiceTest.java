package com.claw.server.domain.advertising;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdProductMediaServiceTest {

    @Mock
    private AdCreativeRepository creativeRepository;

    @Mock
    private AdAccountRepository accountRepository;

    @InjectMocks
    private AdProductMediaService service;

    @Test
    void ingestProductMedia_createsProductCreative() {
        AdAccount account = AdAccount.builder().id(7L).build();
        when(accountRepository.findByOwnerId(7L)).thenReturn(Optional.of(account));
        when(creativeRepository.save(any(AdCreative.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductMediaEvent evt = new ProductMediaEvent(100L, 7L, "http://x/v.mp4", "video/mp4", true);
        AdCreative c = service.ingestProductMedia(evt);

        assertEquals("PRODUCT", c.getSource());
        assertEquals("VIDEO", c.getType());
        assertEquals("http://x/v.mp4", c.getFileUrl());
        assertEquals(7L, c.getAccountId());
        assertTrue(c.getWhiteBg());
        verify(creativeRepository).save(c);
    }

    @Test
    void ingestProductMedia_imageTypeForNonVideo() {
        AdAccount account = AdAccount.builder().id(7L).build();
        when(accountRepository.findByOwnerId(7L)).thenReturn(Optional.of(account));
        when(creativeRepository.save(any(AdCreative.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductMediaEvent evt = new ProductMediaEvent(100L, 7L, "http://x/i.png", "image/png", false);
        assertEquals("IMAGE", service.ingestProductMedia(evt).getType());
    }

    @Test
    void ingestProductMedia_throwsWhenAccountMissing() {
        when(accountRepository.findByOwnerId(99L)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.ingestProductMedia(new ProductMediaEvent(1L, 99L, "u", "m", false)));
        assertTrue(ex.getMessage().contains("ad.account.not.found"));
        verify(creativeRepository, never()).save(any());
    }
}
