package com.claw.server.common.security;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 安全配置：无状态 JWT + 资源白名单。
 *
 * <p>放行：登录/注册、健康检查、Swagger、公开静态资源；
 * 其余一律要求携带有效 Bearer token（由 {@link JwtAuthFilter} 解析）。
 *
 * <p><b>为什么必须显式配置 401 / 403 两个出口：</b>
 * 本类此前只写了 {@code authorizeHttpRequests}，没配 {@code exceptionHandling}。
 * Spring Security 在既没启用 {@code httpBasic}/{@code formLogin}、又未指定
 * {@code AuthenticationEntryPoint} 时，会回落到 {@code Http403ForbiddenEntryPoint} ——
 * 于是 <b>所有未认证请求都返回 HTTP 403 且 body 为空</b>。云端真机实测：
 * <pre>
 *   GET /api/v1/admin/dashboard（无 token）   → HTTP 403，Content-Length: 0
 *   GET /api/v1/admin/dashboard（伪造 token） → HTTP 403，Content-Length: 0
 * </pre>
 *
 * <p>这会踩两个坑：
 * <ol>
 *   <li>前端 {@code web/src/api.js} 的响应拦截只在 {@code status === 401} 时清 token
 *       并跳登录页；后端从不返回 401 → <b>登录态过期永远不会跳登录页</b>，
 *       用户只会看到页面连续报错，且拿不到任何可读提示；</li>
 *   <li>空 body 让前端连一句本地化错误都取不到。</li>
 * </ol>
 *
 * <p>语义按项目既有约定收敛（错误码前三位 ≈ HTTP 状态码，见 {@link BizException#httpStatus()}）：
 * <ul>
 *   <li><b>未认证</b>（没带 token / token 非法或过期）→ <b>401</b> + {@code code 40100}；</li>
 *   <li><b>已认证但被拒</b>（CSRF 拦截，或日后新增的 {@code hasAuthority} 规则不满足）
 *       → <b>403</b> + {@code code 40301}。</li>
 * </ul>
 * 注意「权限位不足」（{@code @RequirePermission}）走的是 AOP 抛 {@link BizException#forbidden}，
 * 由 {@code GlobalExceptionHandler} 转成 403 JSON，不经过本类 handler，两者互不干扰。
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    /**
     * 用于把 messageCode 翻成当前请求语言，口径与 GlobalExceptionHandler 一致。
     * 这两个 handler 在 Spring Security 过滤器链里执行、早于 DispatcherServlet，
     * 拿不到 {@code @RestControllerAdvice}，所以必须自己翻。
     */
    private final MessageSource messageSource;

    /** 用于把 ApiResult 序列化成与业务接口完全一致的响应体。 */
    private final ObjectMapper objectMapper;

    /**
     * 仅开发/联调态生效的鉴权放开开关。
     * 默认 false：生产态仍强制 JWT（.anyRequest().authenticated()）。
     * 设为 true（启动参数 -Dclaw.security.dev-open-access=true）时，所有请求放行，
     * 便于本地用真实数据库体验全链路，不依赖前端演示 token。
     */
    @Value("${claw.security.dev-open-access:false}")
    private boolean devOpenAccess;

    /**
     * 生产护栏：dev-open-access 会放开全部接口与鉴权，属高危开关。
     * 仅当本开关 {@code claw.security.dev-open-access-ack=true} 同时显式设置时才允许启用，
     * 防止生产环境因误传 -Dclaw.security.dev-open-access=true 而被整体放开。
     */
    @Value("${claw.security.dev-open-access-ack:false}")
    private boolean devOpenAccessAck;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, MessageSource messageSource, ObjectMapper objectMapper) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.messageSource = messageSource;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 鉴权失败出口：必须显式配置，否则回落 Http403ForbiddenEntryPoint（403 空 body）。
            // 详见类注释。
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(apiAuthenticationEntryPoint())
                .accessDeniedHandler(apiAccessDeniedHandler())
            );

        if (devOpenAccess) {
            // 生产护栏：dev-open-access 会放开全部接口与鉴权，属高危开关。
            // 必须同时显式设置 claw.security.dev-open-access-ack=true 方能启用，
            // 防止生产环境因误传 -Dclaw.security.dev-open-access=true 而被整体放开。
            if (!devOpenAccessAck) {
                throw new IllegalStateException(
                        "安全护栏拦截：claw.security.dev-open-access=true 但缺少 "
                                + "claw.security.dev-open-access-ack=true 显式确认。"
                                + "该开关会放开全部接口与鉴权，禁止在生产环境启用；"
                                + "仅允许本地开发/联调时同时设置两个开关。");
            }
            // 开发态：放开全部接口，直接对接真实数据库体验。
            // 该分支下不会产生授权失败，两个出口自然不会被触发。
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            // 生产态：仅白名单免鉴权，其余强制 JWT。
            http.authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/auth/**",
                    "/api/v1/iot/auth",
                    "/api/v1/ping",
                    "/api/v1/countries/**",
                    "/api/v1/jurisdictions/**",
                    // 支付网关回调 webhook：以共享密钥 X-Callback-Token 鉴权（见 PaymentController.verifyCallbackAuth），
                    // 不走用户 JWT，故在此放行由网关直连；未配置密钥时接口 fail-closed 拒绝。
                    // 注意：Spring Security 6 默认 PathPatternParser 要求 ** 仅能出现在路径末尾，
                    // 回调路径按控制器实际路由（单段资源 id）写成 * 通配，避免 "No more pattern data allowed after **" 解析异常。
                    "/api/v1/payments/*/callback",
                    "/api/v1/payments/txns/*/fail",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/actuator/health"
                ).permitAll()
                .anyRequest().authenticated()
            );
        }

        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 未认证出口：401 + {@code ApiResult(code=40100)}。
     *
     * <p>包级可见（而非 private）是为了让同包单测能直接断言其行为，
     * 不必为了测两个出口就启动整个 Web 上下文。
     *
     * <p><b>不要设置 {@code WWW-Authenticate} 响应头</b> —— 一旦设置，浏览器会对
     * 受保护接口弹出原生 basic-auth 登录框，而本项目用的是前端页面内登录。
     */
    AuthenticationEntryPoint apiAuthenticationEntryPoint() {
        return (request, response, authException) ->
                writeError(response, HttpStatus.UNAUTHORIZED,
                        BizException.UNAUTHORIZED, "error.auth.unauthenticated");
    }

    /**
     * 已认证但被拒出口：403 + {@code ApiResult(code=40301)}。
     *
     * <p>当前编排下「已认证」必过 {@code anyRequest().authenticated()}，所以这条主要覆盖
     * CSRF 拦截与日后新增的 {@code hasAuthority} 规则 —— 属防御性配置：
     * 一旦有人重新打开 CSRF 或加细粒度规则，前端拿到的仍是结构化 JSON 而不是空 body。
     */
    AccessDeniedHandler apiAccessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                writeError(response, HttpStatus.FORBIDDEN,
                        BizException.FORBIDDEN, "error.auth.forbidden");
    }

    /**
     * 写出统一的错误响应体。
     *
     * <p>先序列化成字符串、再写响应：这样序列化异常发生在**响应提交之前**，
     * 可以安全降级成一段最小 JSON，不会出现「响应已提交后再抛异常」的二次报错。
     * 消息用 {@code getMessage(..., 默认值 = key)} 取，key 缺失时退化成 key 本身，
     * 而不是抛 {@code NoSuchMessageException} 把 401 变成 500。
     */
    private void writeError(HttpServletResponse response, HttpStatus status, int code, String messageKey)
            throws IOException {
        String message = messageSource.getMessage(messageKey, null, messageKey, LocaleContextHolder.getLocale());
        String body;
        try {
            body = objectMapper.writeValueAsString(ApiResult.error(code, message));
        } catch (JsonProcessingException e) {
            body = "{\"code\":" + code + ",\"message\":\"" + messageKey + "\",\"data\":null}";
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
