package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.domain.autonomy.AutonomyTask;
import com.claw.server.domain.autonomy.AutonomyTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link AutonomyTaskController} 的接线测试（无 Spring 上下文，纯 Mockito）。
 * 覆盖创建任务成功、按 taskId 更新进度缺失→40463、按资产上报进度缺失→40464。
 */
@ExtendWith(MockitoExtension.class)
class AutonomyTaskControllerTest {

    @Mock
    private AutonomyTaskService service;

    @InjectMocks
    private AutonomyTaskController controller;

    @Test
    void createTask_returnsTask() {
        AutonomyTask task = AutonomyTask.builder()
                .assetId(1L).subtype("DELIVERY").status("PENDING").progressPct(0).build();
        when(service.createTask(anyLong(), anyString(), anyString())).thenReturn(task);

        ApiResult<AutonomyTask> result = controller.createTask(1L,
                new AutonomyTaskController.CreateTask("DELIVERY", "[]"));

        assertEquals("DELIVERY", result.data().getSubtype());
    }

    @Test
    void updateProgress_missingTask_throws40463() {
        when(service.updateProgress(anyLong(), anyInt()))
                .thenThrow(new IllegalArgumentException("autonomy.task.not.found:99"));

        BizException ex = assertThrows(BizException.class,
                () -> controller.updateProgress(1L, 99L, new AutonomyTaskController.ProgressReq(50)));

        assertEquals(40463, ex.getCode());
    }

    @Test
    void reportProgress_missing_throws40464() {
        when(service.reportProgress(anyLong(), anyInt()))
                .thenThrow(new IllegalArgumentException("autonomy.task.not.found.for.asset:1"));

        BizException ex = assertThrows(BizException.class,
                () -> controller.reportProgress(1L, new AutonomyTaskController.ProgressReq(50)));

        assertEquals(40464, ex.getCode());
    }
}
