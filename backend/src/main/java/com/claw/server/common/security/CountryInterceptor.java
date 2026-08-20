package com.claw.server.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 法域拦截器：从 {@code X-Country-Code} 头解析当前国家，缺省回退柬埔寨试点。
 * 租户 id 先按固定试点租户（1）设置；后续可由 countries↔tenants 映射动态解析。
 */
public class CountryInterceptor implements HandlerInterceptor {

    static final String HEADER = "X-Country-Code";

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        String code = request.getHeader(HEADER);
        if (code == null || code.isBlank()) {
            code = CountryContext.DEFAULT_COUNTRY;
        } else {
            code = code.trim().toUpperCase();
        }
        CountryContext.set(code, CountryContext.DEFAULT_TENANT);
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        CountryContext.clear();
    }
}
