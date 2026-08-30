package com.claw.server.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级权限注解（权限通电内核）。
 *
 * <p>标注在 Controller 的「写 / 导出 / 下载」方法上，由 {@code web.support.PermissionAspect}
 * 在请求进入时校验当前登录用户是否持有注解声明的权限位。权限位形如
 * {@code "asset:create"} / {@code "asset:export"}（见 V40 种子菜单里的按钮权限点）。
 *
 * <p>约定：
 * <ul>
 *   <li>仅对 admin 写接口（POST/PUT/DELETE）与导出/下载接口加本注解；读接口（GET 列表/详情）一律不动。</li>
 *   <li>支持同时声明多个权限位，命中任一即放行（OR 语义）。</li>
 *   <li>权限位集合含通配符 {@code "*"} 视为平台超级管理员，放行一切。</li>
 *   <li>本注解位于 common 层，禁止反向依赖 domain（ArchUnit 守护）。</li>
 * </ul>
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {
    /** 所需权限位（可多个，命中其一即可）。 */
    String[] value();
}
