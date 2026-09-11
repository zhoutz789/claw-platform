package com.claw.server.common.security;

import com.claw.server.config.I18nConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 鉴权失败出口「确实被装配进过滤器链」的回归测试。
 *
 * <p>{@link SecurityConfigAuthErrorTest} 只证明两个出口自身的语义正确，
 * 但它直接调用出口方法 —— 如果有人把 {@code filterChain} 里的
 * {@code .exceptionHandling(...)} 整段删掉，那个测试<b>照样全绿</b>，
 * 而线上又会退回到「未认证 = 403 空 body」。
 *
 * <p><b>为什么不用 MockMvc 发请求来验证：</b>在 {@code @WebMvcTest} 切片里，
 * 无论走 {@code @AutoConfigureMockMvc} 自动挂载还是手动 {@code springSecurity()}，
 * 受保护路径都直接返回 200（实测），驱动真实 {@code FilterChainProxy} 也一样 ——
 * 切片下这套组合行为反常，用它做断言会「假绿」，反而比没有测试更危险。
 *
 * <p>因此这里改为对**已装配好的 {@link SecurityFilterChain} 做结构断言**：
 * 取出链条里的 {@link ExceptionTranslationFilter}，检查它持有的
 * {@code authenticationEntryPoint} / {@code accessDeniedHandler} 是不是
 * Spring Security 的默认兜底实现。
 *
 * <p>为什么这个断言真能抓住回归：{@code ExceptionHandlingConfigurer} 是
 * Spring Security 默认就会应用的，所以<b>删掉 exceptionHandling 之后
 * ExceptionTranslationFilter 依然存在</b>，只是它的出口会退回
 * {@link Http403ForbiddenEntryPoint}（就是线上 403 空 body 的元凶）与
 * {@link AccessDeniedHandlerImpl}。所以「不是默认实现」才是有效断言，
 * 「过滤器存在」不是。
 *
 * <p>用 {@code @WebMvcTest} 切片（不加载 JPA / DataSource / Flyway），
 * 属于快速纯单测，不是 IT。
 */
@WebMvcTest(controllers = SecurityConfigFilterChainTest.ProbeController.class)
@Import({SecurityConfig.class, I18nConfig.class})
// 显式锁定鉴权开关，避免被 application.yml 或环境变量意外打开（dev-open-access 会 permitAll）
@TestPropertySource(properties = {
        "claw.security.dev-open-access=false",
        "claw.security.dev-open-access-ack=false"
})
class SecurityConfigFilterChainTest {

    /** 应用实际装配出来的那条安全过滤器链（Servlet 容器用的就是它）。 */
    @Autowired
    private SecurityFilterChain securityFilterChain;

    /** JwtAuthFilter 是 {@code @Component}，切片里替换为 mock，避免拉起 JWT 解析依赖。 */
    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    /** 占位控制器，让切片成为一个真实的 Web 上下文（请求行为不参与断言）。 */
    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/admin/probe")
        String protectedProbe() {
            return "protected-ok";
        }
    }

    private ExceptionTranslationFilter exceptionTranslationFilter() {
        return securityFilterChain.getFilters().stream()
                .filter(ExceptionTranslationFilter.class::isInstance)
                .map(ExceptionTranslationFilter.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "过滤器链里没有 ExceptionTranslationFilter —— 安全配置被破坏"));
    }

    @Test
    @DisplayName("链条上的 401 出口不是 Http403ForbiddenEntryPoint（原缺陷的直接回归）")
    void chainUsesCustomUnauthenticatedEntryPoint() {
        AuthenticationEntryPoint entryPoint = (AuthenticationEntryPoint)
                ReflectionTestUtils.getField(exceptionTranslationFilter(), "authenticationEntryPoint");

        assertThat(entryPoint)
                .as("出口未装配")
                .isNotNull();
        assertThat(entryPoint)
                .as("退回 Http403ForbiddenEntryPoint 说明 exceptionHandling 被删了 —— "
                        + "未认证会变成 403 空 body，前端登录态过期不会跳登录页")
                .isNotInstanceOf(Http403ForbiddenEntryPoint.class);
    }

    @Test
    @DisplayName("链条上的 403 出口不是 AccessDeniedHandlerImpl 默认实现")
    void chainUsesCustomDeniedHandler() {
        AccessDeniedHandler deniedHandler = (AccessDeniedHandler)
                ReflectionTestUtils.getField(exceptionTranslationFilter(), "accessDeniedHandler");

        assertThat(deniedHandler).isNotNull();
        assertThat(deniedHandler).isNotInstanceOf(AccessDeniedHandlerImpl.class);
    }

    @Test
    @DisplayName("链条上的出口确实按 401 / 403 语义写响应（与 SecurityConfigAuthErrorTest 呼应）")
    void chainEntryPointsProduceExpectedStatusCodes() throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/admin/probe");
        var response = new org.springframework.mock.web.MockHttpServletResponse();

        ((AuthenticationEntryPoint) ReflectionTestUtils.getField(
                exceptionTranslationFilter(), "authenticationEntryPoint"))
                .commence(request, response, new org.springframework.security.authentication
                        .InsufficientAuthenticationException("no token"));
        assertThat(response.getStatus()).isEqualTo(401);

        var deniedResponse = new org.springframework.mock.web.MockHttpServletResponse();
        ((AccessDeniedHandler) ReflectionTestUtils.getField(
                exceptionTranslationFilter(), "accessDeniedHandler"))
                .handle(request, deniedResponse, new AccessDeniedException("denied"));
        assertThat(deniedResponse.getStatus()).isEqualTo(403);
    }
}
