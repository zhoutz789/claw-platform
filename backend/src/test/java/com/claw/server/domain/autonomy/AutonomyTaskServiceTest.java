package com.claw.server.domain.autonomy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutonomyTaskServiceTest {

    @Mock
    private AutonomyTaskRepository taskRepository;

    @InjectMocks
    private AutonomyTaskService service;

    @Test
    void createTask_setsPendingAndZeroProgress() {
        when(taskRepository.save(any(AutonomyTask.class))).thenAnswer(inv -> inv.getArgument(0));

        AutonomyTask task = service.createTask(1L, "sweep", "[]");

        assertEquals("PENDING", task.getStatus());
        assertEquals(0, task.getProgressPct());
        assertEquals(1L, task.getAssetId());
    }

    @Test
    void updateProgress_completesAndClampsOver100() {
        AutonomyTask task = AutonomyTask.builder()
                .id(5L).assetId(1L).status("PENDING").progressPct(0).build();
        when(taskRepository.findById(5L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(AutonomyTask.class))).thenAnswer(inv -> inv.getArgument(0));

        AutonomyTask updated = service.updateProgress(5L, 150);

        assertEquals(100, updated.getProgressPct());
        assertEquals("COMPLETED", updated.getStatus());
    }

    @Test
    void updateProgress_keepsPendingBelow100() {
        AutonomyTask task = AutonomyTask.builder()
                .id(5L).assetId(1L).status("PENDING").progressPct(0).build();
        when(taskRepository.findById(5L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(AutonomyTask.class))).thenAnswer(inv -> inv.getArgument(0));

        AutonomyTask updated = service.updateProgress(5L, 60);

        assertEquals(60, updated.getProgressPct());
        assertEquals("PENDING", updated.getStatus());
    }

    @Test
    void updateProgress_throwsWhenMissing() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateProgress(99L, 50));
        assertTrue(ex.getMessage().contains("autonomy.task.not.found"));
    }

    @Test
    void getByAsset_returnsList() {
        AutonomyTask a = AutonomyTask.builder().id(1L).assetId(1L).build();
        AutonomyTask b = AutonomyTask.builder().id(2L).assetId(1L).build();
        when(taskRepository.findByAssetId(1L)).thenReturn(List.of(a, b));

        assertEquals(2, service.getByAsset(1L).size());
    }
}
