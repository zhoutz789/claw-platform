package com.claw.server.domain.user;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短信验证码暂存（dev 内存实现）。
 *
 * <p>⚠️ 生产替换为 SMS 网关 + Redis（带 TTL 与频控），此处仅用于联调：
 * {@code claw.security.sms-dev-echo=true} 时 {@code sendSmsCode} 直接把验证码回显给客户端。
 */
@Component
public class SmsCodeStore {

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private static final long TTL_SECONDS = 300;

    public String issue(String phone) {
        String code = String.format("%06d", (int) (Math.random() * 1_000_000));
        store.put(phone, new Entry(code, Instant.now().plusSeconds(TTL_SECONDS)));
        return code;
    }

    public boolean verify(String phone, String code) {
        Entry e = store.get(phone);
        if (e == null) {
            return false;
        }
        if (e.expireAt.isBefore(Instant.now())) {
            store.remove(phone);
            return false;
        }
        boolean ok = e.code.equals(code);
        if (ok) {
            store.remove(phone);
        }
        return ok;
    }

    private record Entry(String code, Instant expireAt) {
    }
}
