package com.claw.server.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置：无状态 JWT + 资源白名单。
 *
 * <p>放行：登录/注册、健康检查、Swagger、公开静态资源；
 * 其余一律要求携带有效 Bearer token（由 {@link JwtAuthFilter} 解析）。
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

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

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

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
                    "/api/v1/payments/**/callback",
                    "/api/v1/payments/**/fail",
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
}
