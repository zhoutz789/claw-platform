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
class AdCreativeServiceTest {

    @Mock
    private AdCreativeRepository creativeRepository;

    @Mock
    private AdAccountRepository accountRepository;

    @InjectMocks
    private AdCreativeService service;

    @Test
    void uploadCreative_persistsWithUploadSource() {
        when(accountRepository.existsById(1L)).thenReturn(true);
        when(creativeRepository.save(any(AdCreative.class))).thenAnswer(inv -> inv.getArgument(0));

        AdCreative c = service.uploadCreative(1L, "IMAGE", "http://x/a.png", "image/png",
                null, true, "{}");

        assertEquals("UPLOAD", c.getSource());
        assertEquals(1L, c.getAccountId());
        assertEquals("IMAGE", c.getType());
        assertEquals("http://x/a.png", c.getFileUrl());
        verify(creativeRepository).save(c);
    }

    @Test
    void uploadCreative_throwsWhenAccountMissing() {
        when(accountRepository.existsById(99L)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.uploadCreative(99L, "IMAGE", "u", "m", null, false, null));
        assertTrue(ex.getMessage().contains("ad.account.not.found"));
        verify(creativeRepository, never()).save(any());
    }

    @Test
    void get_returnsById() {
        AdCreative c = AdCreative.builder().id(7L).source("UPLOAD").build();
        when(creativeRepository.findById(7L)).thenReturn(Optional.of(c));

        assertEquals(7L, service.get(7L).getId());
    }
}
