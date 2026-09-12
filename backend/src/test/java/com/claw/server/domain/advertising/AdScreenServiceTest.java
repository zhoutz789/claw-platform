package com.claw.server.domain.advertising;

import com.claw.server.common.enums.ScreenTerminalType;
import com.claw.server.common.enums.ScreenType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdScreenServiceTest {

    @Mock
    private AdScreenRepository screenRepository;

    @InjectMocks
    private AdScreenService service;

    @Test
    void registerScreen_persistsOnline() {
        when(screenRepository.save(any(AdScreen.class))).thenAnswer(inv -> inv.getArgument(0));

        AdScreen s = service.registerScreen(ScreenTerminalType.ASSET, 10L, 20L, ScreenType.BODY,
                "{}", "TOUCH", 5L);

        assertEquals("ONLINE", s.getStatus());
        assertEquals(ScreenTerminalType.ASSET, s.getTerminalType());
        assertEquals(20L, s.getAssetId());
        verify(screenRepository).save(s);
    }

    @Test
    void registerScreen_mobileAllowsNullAsset() {
        when(screenRepository.save(any(AdScreen.class))).thenAnswer(inv -> inv.getArgument(0));

        AdScreen s = service.registerScreen(ScreenTerminalType.MOBILE, null, null,
                ScreenType.MOBILE, null, null, 5L);

        assertEquals("ONLINE", s.getStatus());
        assertNull(s.getAssetId());
        assertNull(s.getDeviceId());
        verify(screenRepository).save(s);
    }

    @Test
    void listScreens_returnsAll() {
        when(screenRepository.findAll()).thenReturn(List.of(
                AdScreen.builder().id(1L).build(), AdScreen.builder().id(2L).build()));
        assertEquals(2, service.listScreens().size());
    }
}
