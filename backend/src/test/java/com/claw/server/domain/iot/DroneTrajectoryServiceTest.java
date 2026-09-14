package com.claw.server.domain.iot;

import com.claw.server.common.api.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DroneTrajectoryServiceTest {

    @Mock
    private DroneTrajectoryRepository repository;

    @InjectMocks
    private DroneTrajectoryService service;

    @Test
    void append_savesWithNowTimestamp() {
        when(repository.save(any(DroneTrajectory.class))).thenAnswer(inv -> inv.getArgument(0));

        DroneTrajectory result = service.append(10L, new DroneTrajectoryService.TrajectoryPoint(
                11.5564, 104.9282, 120.0, 15.0, 90.0, 88.0, "RTK", "FCU", "FL-001"));

        assertEquals(Long.valueOf(10L), result.getAssetId());
        assertNotNull(result.getTs());
        assertEquals(Double.valueOf(120.0), result.getAltM());
        assertEquals("RTK", result.getPosMode());
        assertEquals("FL-001", result.getFlightNo());
        verify(repository).save(any(DroneTrajectory.class));
    }

    @Test
    void append_rejectsNullPoint() {
        assertThrows(BizException.class, () -> service.append(10L, null));
        verify(repository, never()).save(any());
    }

    @Test
    void query_delegatesToTimeWindow() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-02T00:00:00Z");
        when(repository.findByAssetIdAndTsBetweenOrderByTsAsc(10L, from, to)).thenReturn(List.of());

        service.query(10L, from, to);

        verify(repository).findByAssetIdAndTsBetweenOrderByTsAsc(10L, from, to);
    }
}
