package com.claw.server.domain.iot;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.IoTViews;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BmsControlServiceTest {

    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private DeviceCommandService deviceCommandService;
    @InjectMocks
    private BmsControlService service;

    private Device bmsDevice() {
        return Device.builder()
                .id(1L).deviceNo("BMS-1").deviceType("BATTERY_BMS").assetId(10L).tenantId(1L).build();
    }

    @Test
    void heaterOn_issues_HEATER_ON_with_state_1() {
        when(deviceRepository.findByDeviceNo("BMS-1")).thenReturn(java.util.Optional.of(bmsDevice()));
        when(deviceCommandService.issue(eq("BMS-1"), eq("HEATER_ON"), any()))
                .thenReturn(org.mockito.Mockito.mock(IoTViews.CommandView.class));

        service.heaterOn("BMS-1");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(deviceCommandService).issue(eq("BMS-1"), eq("HEATER_ON"), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) captor.getValue();
        assertEquals(1, params.get("state"));
    }

    @Test
    void setHeaterTemp_passes_targetTemp() {
        when(deviceRepository.findByDeviceNo("BMS-1")).thenReturn(java.util.Optional.of(bmsDevice()));
        when(deviceCommandService.issue(eq("BMS-1"), eq("SET_HEATER_TEMP"), any()))
                .thenReturn(org.mockito.Mockito.mock(IoTViews.CommandView.class));

        service.setHeaterTemp("BMS-1", new BigDecimal("28.5"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(deviceCommandService).issue(eq("BMS-1"), eq("SET_HEATER_TEMP"), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) captor.getValue();
        assertEquals(new BigDecimal("28.5"), params.get("targetTemp"));
    }

    @Test
    void non_bms_device_rejected() {
        Device vehicle = Device.builder()
                .id(2L).deviceNo("VEH-1").deviceType("VEHICLE_TCU").assetId(20L).tenantId(1L).build();
        when(deviceRepository.findByDeviceNo("VEH-1")).thenReturn(java.util.Optional.of(vehicle));

        assertThrows(BizException.class, () -> service.heaterOn("VEH-1"));
    }

    @Test
    void setHeaterTemp_without_param_throws() {
        // BmsCommand.toParams 应要求 targetTemp；经由 service 透传空 params。
        when(deviceRepository.findByDeviceNo("BMS-1")).thenReturn(java.util.Optional.of(bmsDevice()));

        Map<String, Object> empty = new HashMap<>();
        assertThrows(IllegalArgumentException.class,
                () -> service.issueCommand("BMS-1", BmsCommand.SET_HEATER_TEMP, empty));
    }
}
