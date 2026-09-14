package com.claw.server.web.v1;

import com.claw.server.common.enums.ClearingScene;
import com.claw.server.common.security.JwtAuthFilter;
import com.claw.server.common.security.JwtUtil;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.clearing.ClearingInstruction;
import com.claw.server.domain.clearing.ClearingInstructionRepository;
import com.claw.server.domain.clearing.SettlementBatch;
import com.claw.server.domain.clearing.SettlementBatchRepository;
import com.claw.server.domain.clearing.SettlementBatchService;
import com.claw.server.domain.clearing.SettlementRule;
import com.claw.server.domain.clearing.SettlementRuleRepository;
import com.claw.server.domain.clearing.SuspenseEntry;
import com.claw.server.domain.clearing.SuspenseEntryRepository;
import com.claw.server.domain.clearing.SuspenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台清分控制器单测（T10）：路由 + @RequirePermission 注解 + service 调用 + 200。
 *
 * <p>切片隔离：{@code @WebMvcTest} 只加载 web 层；安全过滤器链由
 * {@code addFilters = false} 关闭（与 StationConsignmentInboundIT 同思路），请求直达控制器，
 * 不依赖登录态。{@code @RequirePermission} 的运行时拦截由 PermissionAspect 在全量上下文生效，
 * 此处用反射断言注解确实存在且权限位正确，覆盖「注解」校验。
 */
@WebMvcTest(controllers = AdminClearingController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminClearingControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SettlementRuleRepository settlementRuleRepository;
    @MockBean
    private ClearingInstructionRepository clearingInstructionRepository;
    @MockBean
    private SettlementBatchRepository settlementBatchRepository;
    @MockBean
    private SettlementBatchService settlementBatchService;
    @MockBean
    private SuspenseEntryRepository suspenseEntryRepository;
    @MockBean
    private SuspenseService suspenseService;

    /** 安全链路依赖：切片不加载 JWT 解析链路，mock 掉避免 SecurityConfig 装配失败。 */
    @MockBean
    private JwtAuthFilter jwtAuthFilter;
    @MockBean
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        when(settlementRuleRepository.findAll()).thenReturn(List.of());
        when(clearingInstructionRepository.findAll()).thenReturn(List.of());
        when(settlementBatchRepository.findAll()).thenReturn(List.of());
        when(suspenseEntryRepository.findAll()).thenReturn(List.of());
        when(settlementBatchService.collect(any(), any(), any())).thenReturn(SettlementBatch.builder().id(1L).build());
        when(settlementBatchService.approve(any(), any())).thenReturn(SettlementBatch.builder().id(1L).build());
        when(settlementBatchService.submit(any())).thenReturn(SettlementBatch.builder().id(1L).build());
        when(suspenseService.resolve(any(), any(), any())).thenReturn(SuspenseEntry.builder().id(1L).build());
    }

    @Test
    @DisplayName("GET /rules 返回 200 并调用仓储")
    void listRules_returns200_and_callsRepo() throws Exception {
        mvc.perform(get("/api/v1/admin/clearing/rules")).andExpect(status().isOk());
        verify(settlementRuleRepository).findAll();
    }

    @Test
    @DisplayName("GET /rules/{id} 返回 200")
    void getRule_returns200() throws Exception {
        when(settlementRuleRepository.findById(1L)).thenReturn(Optional.of(SettlementRule.builder().id(1L).build()));
        mvc.perform(get("/api/v1/admin/clearing/rules/1")).andExpect(status().isOk());
        verify(settlementRuleRepository).findById(1L);
    }

    @Test
    @DisplayName("GET /instructions 返回 200 并调用仓储")
    void listInstructions_returns200_and_callsRepo() throws Exception {
        mvc.perform(get("/api/v1/admin/clearing/instructions")).andExpect(status().isOk());
        verify(clearingInstructionRepository).findAll();
    }

    @Test
    @DisplayName("GET /batches 返回 200 并调用仓储")
    void listBatches_returns200_and_callsRepo() throws Exception {
        mvc.perform(get("/api/v1/admin/clearing/batches")).andExpect(status().isOk());
        verify(settlementBatchRepository).findAll();
    }

    @Test
    @DisplayName("POST /batches/collect 返回 200 并调用 service")
    void collectBatch_returns200_and_callsService() throws Exception {
        String body = "{\"scene\":\"R1\",\"periodStart\":\"2026-01-01T00:00:00Z\","
                + "\"periodEnd\":\"2026-01-31T23:59:59Z\"}";
        mvc.perform(post("/api/v1/admin/clearing/batches/collect")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(settlementBatchService).collect(eq(ClearingScene.R1),
                eq(Instant.parse("2026-01-01T00:00:00Z")), eq(Instant.parse("2026-01-31T23:59:59Z")));
    }

    @Test
    @DisplayName("POST /batches/{id}/approve 返回 200 并调用 service")
    void approveBatch_returns200_and_callsService() throws Exception {
        String body = "{\"operatorId\":7}";
        mvc.perform(post("/api/v1/admin/clearing/batches/1/approve")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(settlementBatchService).approve(eq(1L), eq(7L));
    }

    @Test
    @DisplayName("POST /batches/{id}/submit 返回 200 并调用 service")
    void submitBatch_returns200_and_callsService() throws Exception {
        mvc.perform(post("/api/v1/admin/clearing/batches/1/submit")).andExpect(status().isOk());
        verify(settlementBatchService).submit(eq(1L));
    }

    @Test
    @DisplayName("GET /suspense 返回 200 并调用仓储")
    void listSuspense_returns200_and_callsRepo() throws Exception {
        mvc.perform(get("/api/v1/admin/clearing/suspense")).andExpect(status().isOk());
        verify(suspenseEntryRepository).findAll();
    }

    @Test
    @DisplayName("POST /suspense/{id}/resolve 返回 200 并调用 service")
    void resolveSuspense_returns200_and_callsService() throws Exception {
        String body = "{\"operatorId\":7,\"resolution\":\"已冲销\"}";
        mvc.perform(post("/api/v1/admin/clearing/suspense/1/resolve")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(suspenseService).resolve(eq(1L), eq(7L), eq("已冲销"));
    }

    @Test
    @DisplayName("所有写/读端点均声明 @RequirePermission 且权限位正确")
    void endpoints_declare_require_permission() {
        Map<String, String> expected = Map.of(
                "listRules", "finance:clearing:view",
                "getRule", "finance:clearing:view",
                "listInstructions", "finance:clearing:view",
                "listBatches", "finance:clearing:view",
                "collectBatch", "finance:clearing:manage",
                "approveBatch", "finance:clearing:manage",
                "submitBatch", "finance:clearing:manage",
                "listSuspense", "finance:clearing:view",
                "resolveSuspense", "finance:clearing:manage");
        for (Method m : AdminClearingController.class.getDeclaredMethods()) {
            if (!expected.containsKey(m.getName())) {
                continue;
            }
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            assertNotNull(ann, m.getName() + " 应声明 @RequirePermission");
            assertTrue(List.of(ann.value()).contains(expected.get(m.getName())),
                    m.getName() + " 应要求权限位 " + expected.get(m.getName()));
        }
    }
}
