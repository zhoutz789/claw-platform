package com.claw.server.web.support;

import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.DataScope;
import com.claw.server.common.security.DataScopeContext;
import com.claw.server.common.security.DataScopeResult;
import com.claw.server.domain.role.DataScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 数据权限切面（权限通电 P1-T03）：在标注 {@link DataScope} 的读列表方法执行前，
 * 解析当前用户数据范围并写入 {@link DataScopeContext}（ThreadLocal），方法退出后清理。
 *
 * <p>设计要点：
 * <ul>
 *   <li>位于 web.support，依赖 common.security（注解/上下文）与 domain.role.DataScopeService（解析），
 *       不违反 ArchUnit「common 不得依赖 domain」边界。</li>
 *   <li>@Order(2)，在 {@code PermissionAspect}(@Order(1)) 之后执行；二者互补：先鉴权，再定数据范围。</li>
 *   <li>开发态 {@code claw.security.dev-open-access=true} <b>同时放开鉴权与数据范围</b>：
 *       鉴权由 {@code PermissionAspect} 跳过，数据范围由本切面写入
 *       {@link DataScopeResult#all()}（scope=ALL）到上下文，使下游
 *       {@link com.claw.server.common.security.DataScopeSpec} 退化为 {@code conjunction()} 不过滤。
 *       <br>注意：此处必须<b>显式写入 ALL</b> 而非留空——各域 list 方法在上下文为 null 时会回落到
 *       {@code DataScopeService.resolve(...)}，那条回落路径<b>仍会过滤</b>，会让开发态放开形同虚设。</li>
 *   <li>@After（含异常路径）清理 ThreadLocal，防止请求串号。</li>
 * </ul>
 */
@Aspect
@Component
@Order(2)
@Slf4j
@RequiredArgsConstructor
public class DataScopeAspect {

    private final DataScopeService dataScopeService;

    /**
     * 开发态放开开关：为 {@code true} 时同时放开鉴权（{@code PermissionAspect}）与数据范围（本切面）。
     * 由 Spring 注入，故非 final。
     */
    @Value("${claw.security.dev-open-access:false}")
    private boolean devOpenAccess;

    @Before("@annotation(ds)")
    public void bind(JoinPoint joinPoint, DataScope ds) {
        if (devOpenAccess) {
            // 开发态放开：写入 ALL 结果，使下游 Specification 退化为 conjunction（不过滤），与 PermissionAspect 一致。
            DataScopeContext.set(DataScopeResult.all());
            return;
        }
        Long userId = AuthContext.currentUserId();
        DataScopeResult result = dataScopeService.resolve(userId);
        DataScopeContext.set(result);
    }

    @After("@annotation(com.claw.server.common.security.DataScope)")
    public void clear() {
        DataScopeContext.clear();
    }
}
