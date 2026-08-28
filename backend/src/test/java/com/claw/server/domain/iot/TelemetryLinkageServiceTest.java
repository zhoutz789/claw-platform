package com.claw.server.domain.iot;

import com.claw.server.common.enums.LinkageDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 遥测联动编排单测：验证一次遥测正确驱动四向闭环。
 * 仅单测编排层（mock ApplicationEventPublisher + DeviceLinkageEventRepository），
 * 监听器与下游副作用由 Spring 领域事件在集成期触发，不在此验证。
 */
@ExtendWith(MockitoExtension.class)
class TelemetryLinkageServiceTest {

    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private DeviceLinkageEventRepository linkageEventRepository;
    @InjectMocks private TelemetryLinkageService service;

    private Device drone() {
        return Device.builder().id(1L).assetId(10L).deviceType("DRONE_FCU").build();
    }

    private Device battery() {
        return Device.builder().id(2L).assetId(20L).deviceType("BATTERY_BMS").build();
    }

    private TelemetryLatest latest(Long deviceId, Long assetId, BigDecimal soc, BigDecimal soh, String faults) {
        return TelemetryLatest.builder().id(deviceId).deviceId(deviceId).assetId(assetId)
                .soc(soc).soh(soh).speed(new BigDecimal("20")).temp(new BigDecimal("30"))
                .faults(faults).build();
    }

    @Test
    void drone_low_battery_triggers_risk_and_always_asset_revenue() {
        when(linkageEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);

        service.evaluate(latest(1L, 10L, new BigDecimal("10"), new BigDecimal("95"), null), drone());

        verify(eventPublisher, atLeast(1)).publishEvent(events.capture());
        assertTrue(events.getAllValues().stream().anyMatch(e -> e instanceof RiskTriggerEvent));
        assertTrue(events.getAllValues().stream().anyMatch(e -> e instanceof RevenueTriggerEvent));

        ArgumentCaptor<DeviceLinkageEvent> cap = ArgumentCaptor.forClass(DeviceLinkageEvent.class);
        verify(linkageEventRepository, times(3)).save(cap.capture());
        List<LinkageDirection> dirs = cap.getAllValues().stream().map(DeviceLinkageEvent::getDirection).toList();
        assertTrue(dirs.contains(LinkageDirection.ASSET_UPDATE));
        assertTrue(dirs.contains(LinkageDirection.REVENUE));
        assertTrue(dirs.contains(LinkageDirection.RISK));
    }

    @Test
    void battery_low_soh_triggers_lifecycle_no_risk() {
        when(linkageEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);

        service.evaluate(latest(2L, 20L, new BigDecimal("80"), new BigDecimal("60"), null), battery());

        verify(eventPublisher, atLeast(1)).publishEvent(events.capture());
        assertTrue(events.getAllValues().stream().anyMatch(e -> e instanceof LifecycleTriggerEvent));
        assertTrue(events.getAllValues().stream().noneMatch(e -> e instanceof RiskTriggerEvent));

        ArgumentCaptor<DeviceLinkageEvent> cap = ArgumentCaptor.forClass(DeviceLinkageEvent.class);
        verify(linkageEventRepository, times(3)).save(cap.capture());
        assertTrue(cap.getAllValues().stream().map(DeviceLinkageEvent::getDirection)
                .anyMatch(d -> d == LinkageDirection.LIFECYCLE));
    }

    @Test
    void healthy_asset_only_asset_update_and_revenue() {
        when(linkageEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);

        service.evaluate(latest(2L, 20L, new BigDecimal("80"), new BigDecimal("95"), null), battery());

        verify(eventPublisher, atLeast(1)).publishEvent(events.capture());
        assertTrue(events.getAllValues().stream().noneMatch(e -> e instanceof RiskTriggerEvent));
        assertTrue(events.getAllValues().stream().noneMatch(e -> e instanceof LifecycleTriggerEvent));
        assertTrue(events.getAllValues().stream().anyMatch(e -> e instanceof RevenueTriggerEvent));

        ArgumentCaptor<DeviceLinkageEvent> cap = ArgumentCaptor.forClass(DeviceLinkageEvent.class);
        verify(linkageEventRepository, times(2)).save(cap.capture());
        List<LinkageDirection> dirs = cap.getAllValues().stream().map(DeviceLinkageEvent::getDirection).toList();
        assertEquals(2, dirs.size());
        assertTrue(dirs.contains(LinkageDirection.ASSET_UPDATE));
        assertTrue(dirs.contains(LinkageDirection.REVENUE));
    }
}
