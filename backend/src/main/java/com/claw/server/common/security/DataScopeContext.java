package com.claw.server.common.security;

/**
 * 数据范围结果 ThreadLocal 持有器（权限通电 P1-T03）。
 *
 * <p>{@link DataScope} 标注的方法由 {@code web.support.DataScopeAspect} 在进入时写入
 * {@link DataScopeResult}、在退出（@After，含异常）时清理；方法体内读取并交给
 * {@link DataScopeSpec} 翻译为 JPA {@code Specification}。
 *
 * <p>放 common/security：domain 服务会读取它（DataScopeService 产出后由切面写入，域服务消费），
 * 属 common 被 domain 依赖，符合 ArchUnit 边界。
 */
public final class DataScopeContext {

    private static final ThreadLocal<DataScopeResult> HOLDER = new ThreadLocal<>();

    private DataScopeContext() {
    }

    /** 写入当前线程的数据范围结果。 */
    public static void set(DataScopeResult result) {
        HOLDER.set(result);
    }

    /** 读取当前线程的数据范围结果（未设置返回 null，调用方应降级为「不过滤」）。 */
    public static DataScopeResult get() {
        return HOLDER.get();
    }

    /** 清理当前线程的数据范围结果（务必在请求结束时调用，防止串号）。 */
    public static void clear() {
        HOLDER.remove();
    }
}
