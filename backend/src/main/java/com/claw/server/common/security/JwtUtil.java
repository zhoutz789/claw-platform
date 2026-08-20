package com.claw.server.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具：签发与解析登录态 token。
 *
 * <p>仅承载用户标识与手机号，不塞权限（权限走 RBAC + 角色包 + 资产 ACL 运行时计算）。
 * 生产环境密钥必须由环境变量 {@code CLAW_JWT_SECRET} 注入（≥32 字节）。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtUtil(@Value("${claw.security.jwt-secret}") String secret,
                   @Value("${claw.security.jwt-ttl-minutes:1440}") long ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = ttlMinutes * 60_000L;
    }

    public String issue(Long userId, String phone, String roleSummary) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", userId);
        claims.put("phone", phone);
        claims.put("roles", roleSummary == null ? "" : roleSummary);
        Instant now = Instant.now();
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMillis)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(stripBearer(token))
                .getPayload();
    }

    public Long getUserId(String token) {
        return Long.valueOf(parse(token).getSubject());
    }

    private String stripBearer(String token) {
        if (token != null && token.startsWith("Bearer ")) {
            return token.substring(7);
        }
        return token;
    }
}
