package com.claw.server.domain.iot;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EMQX 入站遥测分流单元测试（不连 Broker）：
 * 同一 {@code claw/iot/{deviceNo}/telemetry} topic 同时承载 BMS 与光伏两类设备，
 * 必须按 {@code devices.device_type} 分流，否则光伏报文会被写进 BMS 列组。
 */
@ExtendWith(MockitoExtension.class)
class EmqxMqttInboundAdapterTest {

    private static final String TOPIC = "claw/iot/PV-1/telemetry";

    @Mock
    private IoTService iotService;
    @Mock
    private DeviceCommandService deviceCommandService;
    @Mock
    private BmsTelemetryService bmsTelemetryService;
    @Mock
    private BmsAdapter bmsAdapter;
    @Mock
    private PvTelemetryService pvTelemetryService;
    @Mock
    private PvAdapter pvAdapter;
    @Mock
    private DeviceRepository deviceRepository;
    @InjectMocks
    private EmqxMqttInboundAdapter adapter;

    private Device device(String deviceNo, String deviceType) {
        return Device.builder().id(1L).deviceNo(deviceNo).deviceType(deviceType).assetId(10L).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVERTER", "PV_METER", "WEATHER_STATION", "PV_GATEWAY"})
    void pv_device_types_route_to_pv_service(String deviceType) {
        when(deviceRepository.findByDeviceNo("PV-1")).thenReturn(Optional.of(device("PV-1", deviceType)));

        adapter.messageArrived(TOPIC, message("{\"deviceNo\":\"PV-1\",\"acActivePowerW\":1000}"));

        verify(pvTelemetryService).handleReport(any(), eq("PV-1"));
        verify(bmsTelemetryService, never()).handleReport(any(), any());
    }

    @Test
    void battery_bms_routes_to_bms_service() {
        when(deviceRepository.findByDeviceNo("PV-1")).thenReturn(Optional.of(device("PV-1", "BATTERY_BMS")));

        adapter.messageArrived(TOPIC, message("{\"deviceNo\":\"PV-1\",\"soc\":80}"));

        verify(bmsTelemetryService).handleReport(any(), eq("PV-1"));
        verify(pvTelemetryService, never()).handleReport(any(), any());
    }

    @Test
    void unknown_device_falls_back_to_bms_and_never_pv() {
        when(deviceRepository.findByDeviceNo("PV-1")).thenReturn(Optional.empty());

        adapter.messageArrived(TOPIC, message("{\"deviceNo\":\"PV-1\",\"soc\":80}"));

        verify(bmsTelemetryService).handleReport(any(), eq("PV-1"));
        verify(pvTelemetryService, never()).handleReport(any(), any());
    }

    @Test
    void blank_device_type_falls_back_to_bms() {
        when(deviceRepository.findByDeviceNo("PV-1")).thenReturn(Optional.of(device("PV-1", " ")));

        adapter.messageArrived(TOPIC, message("{\"deviceNo\":\"PV-1\",\"soc\":80}"));

        verify(bmsTelemetryService).handleReport(any(), eq("PV-1"));
        verify(pvTelemetryService, never()).handleReport(any(), any());
    }

    private MqttMessage message(String json) {
        MqttMessage m = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
        return m;
    }
}
