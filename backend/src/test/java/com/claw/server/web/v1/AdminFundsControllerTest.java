package com.claw.server.web.v1;

import com.claw.server.common.enums.CustodyOwnerType;
import com.claw.server.common.security.JwtAuthFilter;
import com.claw.server.common.security.JwtUtil;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.funds.FundsLocation;
import com.claw.server.domain.funds.FundsLocationService;
import com.claw.server.domain.funds.VirtualSubAccount;
import com.claw.server.domain.funds.VirtualSubAccountService;
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
import java.util.List;
import java.util.Map;

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
 * 后台资金控制器单测（T10）：路由 + @RequirePermission 注解 + service 调用 + 200。
 *
 * <p>切片隔离：{@code @WebMvcTest} 只加载 web 层；安全过滤器链由 {@code addFilters = false} 关闭，
 * 请求直达控制器，不依赖登录态。{@code @RequirePermission} 的运行时拦截由 PermissionAspect 在全量上下文
 * 生效，此处用反射断言注解与权限位，覆盖「注解」校验。
 */
@WebMvcTest(controllers = AdminFundsController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminFundsControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private FundsLocationService fundsLocationService;
    @MockBean
    private VirtualSubAccountService virtualSubAccountService;

    /** 安全链路依赖：切片不加载 JWT 解析链路，mock 掉避免 SecurityConfig 装配失败。 */
    @MockBean
    private JwtAuthFilter jwtAuthFilter;
    @MockBean
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        when(fundsLocationService.list()).thenReturn(List.of());
        when(virtualSubAccountService.list()).thenReturn(List.of());
        when(virtualSubAccountService.open(any(), any(), any(), any(), any()))
                .thenReturn(VirtualSubAccount.builder().id(1L).build());
    }

    @Test
    @DisplayName("GET /locations 返回 200 并调用服务")
    void listLocations_returns200_and_callsService() throws Exception {
        mvc.perform(get("/api/v1/admin/funds/locations")).andExpect(status().isOk());
        verify(fundsLocationService).list();
    }

    @Test
    @DisplayName("GET /sub-accounts 返回 200 并调用服务")
    void listSubAccounts_returns200_and_callsService() throws Exception {
        mvc.perform(get("/api/v1/admin/funds/sub-accounts")).andExpect(status().isOk());
        verify(virtualSubAccountService).list();
    }

    @Test
    @DisplayName("POST /sub-accounts/open 返回 200 并调用 service（含参数透传）")
    void openSubAccount_returns200_and_callsService() throws Exception {
        String body = "{\"ownerType\":\"USER\",\"ownerId\":10,\"ownerUserId\":10,"
                + "\"fundsLocationId\":1,\"currency\":\"USD\"}";
        mvc.perform(post("/api/v1/admin/funds/sub-accounts/open")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(virtualSubAccountService).open(eq(CustodyOwnerType.USER), eq(10L), eq(10L), eq(1L), eq("USD"));
    }

    @Test
    @DisplayName("所有端点均声明 @RequirePermission 且权限位正确")
    void endpoints_declare_require_permission() {
        Map<String, String> expected = Map.of(
                "listLocations", "finance:funds:view",
                "listSubAccounts", "finance:funds:view",
                "openSubAccount", "finance:funds:manage");
        for (Method m : AdminFundsController.class.getDeclaredMethods()) {
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
