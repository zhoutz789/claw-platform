package com.claw.server.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限注解（权限通电 P1-T03）：声明某个读列表方法需要按当前登录用户的数据范围过滤。
 *
 * <p>由 {@code web.support.DataScopeAspect} 在方法进入时拦截，调用
 * {@code domain.role.DataScopeService#resolve(Long)} 得到 {@link DataScopeResult} 并写入
 * {@link DataScopeContext}（ThreadLocal）；方法体内取 {@link DataScopeContext#get()} 后通过
 * {@link DataScopeSpec} 翻译成 JPA {@code Specification} 拼接到查询。
 *
 * <p>约定：
 * <ul>
 *   <li>仅标注在「读列表」方法上（Controller 或 Service）；未标注的域行为完全不变（渐进接入）。</li>
 *   <li>{@code entity} 为逻辑实体键（如 {@code "asset"}/{@code "customer_order"}/{@code "user"}），
 *       仅用于可观测/扩展，不影响过滤逻辑。</li>
 *   <li>本注解位于 common 层，禁止反向依赖 domain（ArchUnit 守护）。</li>
 * </ul>
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataScope {
    /** 逻辑实体键（如 asset / customer_order / user）。 */
    String entity();

    /** 多条件组合逻辑（预留，当前未使用）。 */
    Logical logical() default Logical.AND;
}
