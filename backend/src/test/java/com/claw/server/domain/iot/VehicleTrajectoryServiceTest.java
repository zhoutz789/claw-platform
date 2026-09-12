package com.claw.server.domain.iot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VehicleTrajectoryServiceTest {

    @Mock
    private VehicleTrajectoryRepository repository;

    @InjectMocks
    private VehicleTrajectoryService service;

    @Test
    void recordPoint_savesEntityWithNowTimestamp() {
        VehicleTrajectory saved = VehicleTrajectory.builder()
                .id(1L).assetId(10L).t(Instant.now()).lat(31.2).lng(121.4)
                .speedKph(60.0).heading(90.0).odometerKm(12345L).soc(80.0).build();
        when(repository.save(any(VehicleTrajectory.class))).thenReturn(saved);

        VehicleTrajectory result = service.recordPoint(10L, 31.2, 121.4, 60.0, 90.0, 12345L, 80.0);

        assertNotNull(result);
        assertEquals(10L, result.getAssetId());
        verify(repository).save(argThat(p -> p.getAssetId().equals(10L)
                && p.getOdometerKm().equals(12345L)
                && p.getT() != null));
    }

    @Test
    void recordPoint_nullOdometerDefaultsToZero() {
        when(repository.save(any(VehicleTrajectory.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        VehicleTrajectory result = service.recordPoint(10L, null, null, null, null, null, null);
        assertEquals(0L, result.getOdometerKm());
        verify(repository).save(any(VehicleTrajectory.class));
    }

    @Test
    void getLatest_delegatesToRepo() {
        VehicleTrajectory latest = VehicleTrajectory.builder().id(5L).assetId(10L).build();
        when(repository.findTopByAssetIdOrderByTDesc(10L)).thenReturn(Optional.of(latest));

        Optional<VehicleTrajectory> result = service.getLatest(10L);

        assertTrue(result.isPresent());
        assertEquals(5L, result.get().getId());
        verify(repository).findTopByAssetIdOrderByTDesc(10L);
    }

    @Test
    void getTrajectory_delegatesToRepoBetweenQuery() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-02T00:00:00Z");
        when(repository.findByAssetIdAndTBetweenOrderByTAsc(eq(10L), eq(from), eq(to)))
                .thenReturn(List.of());

        service.getTrajectory(10L, from, to);

        verify(repository).findByAssetIdAndTBetweenOrderByTAsc(10L, from, to);
    }
}
