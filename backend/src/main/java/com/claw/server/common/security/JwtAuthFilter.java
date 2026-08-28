package com.claw.server.common.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器：Bearer token → ClawUser → SecurityContext。
 * 解析失败仅清空上下文（不抛异常），由接口权限要求决定最终放行/拒绝。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    /**
     * 仅开发态生效：无有效 token 时注入虚拟操作员，使 requireOperator()/AuthContext
     * 在本地直连真实库体验时不抛未认证。生产态（false）不注入，必须合法 JWT。
     */
    @Value("${claw.security.dev-open-access:false}")
    private boolean devOpenAccess;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parse(header);
                ClawUser user = new ClawUser(
                        Long.valueOf(claims.getSubject()),
                        claims.get("phone", String.class),
                        claims.get("roles", String.class));
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        user, null, AuthorityUtils.createAuthorityList(ClawUser.AUTHORITY_USER));
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }

        // 开发态：未携带有效 token 时填充虚拟操作员，便于全链路真实库体验。
        if (devOpenAccess && SecurityContextHolder.getContext().getAuthentication() == null) {
            ClawUser dev = new ClawUser(1L, "dev-operator", "OPERATOR");
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    dev, null, AuthorityUtils.createAuthorityList(ClawUser.AUTHORITY_USER));
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(request, response);
    }
}
