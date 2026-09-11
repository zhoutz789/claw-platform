package com.claw.server.common.security;

import com.claw.server.common.api.BizException;
import com.claw.server.config.I18nConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 鉴权失败出口的单测。
 *
 * <p>回归的是这个真实缺陷：{@code SecurityConfig} 原本没配 {@code exceptionHandling}，
 * Spring Security 回落到 {@code Http403ForbiddenEntryPoint}，于是
 * <b>所有未认证请求返回 HTTP 403 且 body 为空</b>。因为前端
 * {@code web/src/api.js} 只在 {@code status === 401} 时清 token 跳登录页，
 * 这会让「登录态过期」永远不跳登录页。
 *
 * <p>这里直接调用两个出口（包级可见），不启动 Web 上下文 —— 出口的语义
 * （状态码 / body 结构 / 不设 WWW-Authenticate / 三语本地化）都能覆盖；
 * 「出口确实被注册进过滤器链」这一点由 {@link SecurityConfigFilterChainTest} 负责。
 */
class SecurityConfigAuthErrorTest {

    /** 直接复用生产用的 MessageSource 配置，避免测试里另造一套 i18n 口径。 */
    private static final MessageSource MESSAGE_SOURCE = new I18nConfig().messageSource();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final SecurityConfig CONFIG =
            new SecurityConfig(mock(JwtAuthFilter.class), MESSAGE_SOURCE, OBJECT_MAPPER);

    @Test
    @DisplayName("未认证 → HTTP 401 + ApiResult code 40100（修复前是 403 空 body）")
    void unauthenticated_returns401WithApiResultBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();

        CONFIG.apiAuthenticationEntryPoint()
                .commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);

        JsonNode body = OBJECT_MAPPER.readTree(response.getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(BizException.UNAUTHORIZED);
        assertThat(body.get("message").asText()).isNotBlank();
        assertThat(body.get("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("未认证 → 不设置 WWW-Authenticate，避免浏览器弹原生 basic-auth 登录框")
    void unauthenticated_doesNotChallengeWithBasicAuthPopup() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();

        CONFIG.apiAuthenticationEntryPoint()
                .commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getHeader("WWW-Authenticate")).isNull();
    }

    @Test
    @DisplayName("已认证但被拒 → HTTP 403 + ApiResult code 40301（非空 body）")
    void denied_returns403WithApiResultBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        CONFIG.apiAccessDeniedHandler().handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);

        JsonNode body = OBJECT_MAPPER.readTree(response.getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(BizException.FORBIDDEN);
        assertThat(body.get("message").asText()).isNotBlank();
        assertThat(body.get("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("响应体是 JSON 且带 UTF-8 编码（含高棉语时不能乱码）")
    void responseIsJsonWithUtf8Encoding() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();

        CONFIG.apiAuthenticationEntryPoint()
                .commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
    }

    /**
     * 三个语言包里这两个 key 都必须存在且非空。
     *
     * <p>注意：{@code I18nConfig} 打开了 {@code useCodeAsDefaultMessage}，
     * 所以 key 缺失时不会抛异常，而是**把 key 本身当作文案返回**。
     * 因此断言必须比较「返回值 != key」，只断言非空是抓不到缺失的
     * （这正是本项目对 km 空值特别敏感的原因）。
     */
    @Test
    @DisplayName("error.auth.unauthenticated / error.auth.forbidden 在 zh、en、km 三语均存在且非空")
    void authErrorMessagesResolveInAllThreeLocales() {
        List<Locale> locales = List.of(
                Locale.ENGLISH,
                Locale.forLanguageTag("zh"),
                Locale.forLanguageTag("km"));

        for (Locale locale : locales) {
            for (String key : List.of("error.auth.unauthenticated", "error.auth.forbidden")) {
                String resolved = MESSAGE_SOURCE.getMessage(key, null, locale);
                assertThat(resolved)
                        .as("locale=%s key=%s 必须存在且非空", locale, key)
                        .isNotNull()
                        .isNotBlank();
                assertThat(resolved)
                        .as("locale=%s key=%s 未命中翻译（返回了 key 本身）", locale, key)
                        .isNotEqualTo(key);
            }
        }
    }
}
