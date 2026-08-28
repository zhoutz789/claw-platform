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

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (devOpenAccess) {
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
