package com.claw.server.common.security;

import java.util.Set;

/**
 * 数据范围解析结果（权限通电 P1-T03）。
 *
 * <p>纯数据载体，不依赖任何 domain 包，故置于 common 层，供 domain（DataScopeService 产出）、
 * common（DataScopeSpec 消费）与 web（DataScopeAspect 暂存于 {@link DataScopeContext}）三方共用，
 * 同时遵守 ArchUnit「common 不得依赖 domain」边界。
 *
 * <p>各 scope 含义：
 * <ul>
 *   <li>{@code ALL}                 —— 可见全部数据；</li>
 *   <li>{@code TYPE}               —— 仅可见 {@code allowedTypes} 声明的类型；</li>
 *   <li>{@code DEPARTMENT}         —— 仅可见同部门（{@code departmentId}）；</li>
 *   <li>{@code DEPARTMENT_AND_BELOW}—— 仅可见本部门及以下（{@code orgCodePrefixes} 前缀 LIKE）；</li>
 *   <li>{@code CUSTOM}             —— 仅可见 {@code deptIds} 自定义部门集合；</li>
 *   <li>{@code SELF}               —— 仅可见本人（{@code userId}）名下数据（默认）。</li>
 * </ul>
 */
public record DataScopeResult(
        Scope scope,
        Long departmentId,
        Set<String> allowedTypes,
        Set<Long> deptIds,
        Set<String> orgCodePrefixes,
        Long userId) {

    /**
     * 全部可见的结果常量工厂（权限通电 P1-T03 决策②）。
     *
     * <p>用于开发态放开（{@code claw.security.dev-open-access=true}）时由
     * {@code web.support.DataScopeAspect} 写入 {@link DataScopeContext}，
     * 使下游 {@link DataScopeSpec} 退化为 {@code conjunction()}（不生成任何过滤谓词），
     * 从而避免「上下文为 null → 域方法回落到 DataScopeService.resolve() → 仍然过滤」的旁路。
     *
     * @return scope=ALL、各维度为空集合 / null 的结果
     */
    public static DataScopeResult all() {
        return new DataScopeResult(Scope.ALL, null, Set.of(), Set.of(), Set.of(), null);
    }

    /** 全部可见。 */
    public boolean isAll() {
        return scope == Scope.ALL;
    }

    /** 仅本人。 */
    public boolean isSelfOnly() {
        return scope == Scope.SELF;
    }

    /** 数据范围枚举（与 roles.data_scope 取值对应，并新增 CUSTOM / DEPARTMENT_AND_BELOW）。 */
    public enum Scope {
        ALL,
        CUSTOM,
        DEPARTMENT,
        DEPARTMENT_AND_BELOW,
        SELF,
        TYPE
    }
}
