package com.claw.server.common.security;

/**
 * 当前请求所属法域/国家上下文（共营框架）。
 *
 * <p>由 {@code CountryInterceptor} 在每次请求时根据 {@code X-Country-Code} 头设置，
 * 缺省为柬埔寨试点（KHM）。下游服务据此解析该国的身份/支付/牌照适配器，
 * 实现「核心业务法域无关、合规按国家挂载」。
 */
public final class CountryContext {

    /** 试点国家（默认）。 */
    public static final String DEFAULT_COUNTRY = "KHM";
    /** 试点租户（对齐 V1/V2 既有 tenant_id 默认值）。 */
    public static final long DEFAULT_TENANT = 1L;

    private static final ThreadLocal<String> COUNTRY = ThreadLocal.withInitial(() -> DEFAULT_COUNTRY);
    private static final ThreadLocal<Long> TENANT = ThreadLocal.withInitial(() -> DEFAULT_TENANT);

    private CountryContext() {
    }

    public static void set(String countryCode, long tenantId) {
        COUNTRY.set(countryCode);
        TENANT.set(tenantId);
    }

    public static void clear() {
        COUNTRY.remove();
        TENANT.remove();
    }

    public static String countryCode() {
        return COUNTRY.get();
    }

    public static long tenantId() {
        return TENANT.get();
    }
}
