package com.claw.server.domain.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceAdapterNegotiationServiceTest {

    @Mock
    private AdapterProfileRepository adapterProfileRepository;
    @Mock
    private DeviceAdapterRepository deviceAdapterRepository;
    @Mock
    private DeviceAdapterRegistry registry;

    @InjectMocks
    private DeviceAdapterNegotiationService service;

    // ===================== detectProtocol =====================

    @Test
    void detectProtocol_returnsExplicitProtocol() {
        Map<String, Object> meta = Map.of("protocol", "PYLON_CAN", "vendor", "pylontech");
        assertThat(service.detectProtocol(meta)).isEqualTo("PYLON_CAN");
    }

    @Test
    void detectProtocol_infersFromVendor() {
        Map<String, Object> meta = Map.of("vendor", "OCPP-ChargePoint");
        assertThat(service.detectProtocol(meta)).isEqualTo("OCPP");
    }

    @Test
    void detectProtocol_defaultsToGenericMqtt() {
        Map<String, Object> meta = Map.of("foo", "bar");
        assertThat(service.detectProtocol(meta))
                .isEqualTo(DeviceAdapterNegotiationService.GENERIC_MQTT);
    }

    @Test
    void detectProtocol_nullMetaFallsBack() {
        assertThat(service.detectProtocol(null))
                .isEqualTo(DeviceAdapterNegotiationService.GENERIC_MQTT);
    }

    // ===================== bindDevice =====================

    @Test
    void bindDevice_createsBindingWhenAbsent() {
        Map<String, Object> meta = Map.of("protocol", "GENERIC_MQTT", "vendor", "acme");
        AdapterProfile profile = AdapterProfile.builder()
                .id(10L).protocol("GENERIC_MQTT").status(AdapterProfileStatus.ACTIVE).build();
        when(adapterProfileRepository.findByProtocol("GENERIC_MQTT")).thenReturn(Optional.of(profile));
        when(deviceAdapterRepository.findByDeviceIdAndProfileId(1L, 10L)).thenReturn(Optional.empty());
        when(deviceAdapterRepository.save(any(DeviceAdapter.class))).thenAnswer(inv -> inv.getArgument(0));

        DeviceAdapter result = service.bindDevice(1L, meta);

        assertThat(result.getDeviceId()).isEqualTo(1L);
        assertThat(result.getProfileId()).isEqualTo(10L);
        assertThat(result.getNegotiatedMetaJson()).contains("GENERIC_MQTT");
        verify(deviceAdapterRepository).save(any(DeviceAdapter.class));
    }

    @Test
    void bindDevice_isIdempotentOnRepeat() {
        Map<String, Object> meta = Map.of("protocol", "GENERIC_MQTT");
        AdapterProfile profile = AdapterProfile.builder()
                .id(10L).protocol("GENERIC_MQTT").status(AdapterProfileStatus.ACTIVE).build();
        DeviceAdapter existing = DeviceAdapter.builder().id(5L).deviceId(1L).profileId(10L).build();
        when(adapterProfileRepository.findByProtocol("GENERIC_MQTT")).thenReturn(Optional.of(profile));
        when(deviceAdapterRepository.findByDeviceIdAndProfileId(1L, 10L)).thenReturn(Optional.of(existing));

        DeviceAdapter r1 = service.bindDevice(1L, meta);
        DeviceAdapter r2 = service.bindDevice(1L, meta);

        assertThat(r1.getId()).isEqualTo(5L);
        assertThat(r2.getId()).isEqualTo(5L);
        verify(deviceAdapterRepository, never()).save(any());
    }

    // ===================== normalizeTelemetry via GenericJsonAdapter =====================

    @Test
    void genericJsonAdapter_mapsFieldsCorrectly() {
        Map<String, String> fieldMap = Map.of(
                "assetId", "assetId",
                "lat", "lat",
                "lng", "lng",
                "speedKph", "speed",
                "soc", "soc",
                "soh", "soh",
                "temp", "temp"
        );
        GenericJsonAdapter adapter = new GenericJsonAdapter("GENERIC_MQTT", fieldMap, Map.of());
        String raw = "{\"assetId\":42,\"lat\":11.1,\"lng\":22.2,\"speed\":30.5,\"soc\":88,\"soh\":95,\"temp\":25.3}";

        TelemetryView v = adapter.normalizeTelemetry(raw);

        assertThat(v.assetId()).isEqualTo(42L);
        assertThat(v.lat()).isEqualByComparingTo("11.1");
        assertThat(v.lng()).isEqualByComparingTo("22.2");
        assertThat(v.speedKph()).isEqualByComparingTo("30.5");
        assertThat(v.soc()).isEqualByComparingTo("88");
        assertThat(v.soh()).isEqualByComparingTo("95");
        assertThat(v.temp()).isEqualByComparingTo("25.3");
    }

    @Test
    void genericJsonAdapter_buildDownlinkRendersTemplate() {
        Map<String, String> templates = Map.of("SET_SPEED", "speed=${speed}");
        GenericJsonAdapter adapter = new GenericJsonAdapter("GENERIC_MQTT", Map.of(), templates);

        String downlink = adapter.buildDownlink("SET_SPEED", Map.of("speed", 30));

        assertThat(downlink).isEqualTo("speed=30");
    }
}
