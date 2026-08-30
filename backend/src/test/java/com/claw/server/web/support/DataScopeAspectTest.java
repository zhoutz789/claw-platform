package com.claw.server.web.support;

import com.claw.server.common.security.DataScope;
import com.claw.server.common.security.DataScopeContext;
import com.claw.server.common.security.DataScopeResult;
import com.claw.server.domain.role.DataScopeService;
import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据权限切面单元测试（Mockito，无需 DB）。
 *
 * <p>覆盖：① 正常态写入 ThreadLocal 并在 @After 清理；② <b>开发态放开必须写入 ALL</b>（决策②）。
 *
 * <p>关于 ②：早期实现是「开发态直接 return，不写上下文」，但各域 list 方法在
 * {@code DataScopeContext.get() == null} 时会回落到 {@code dataScopeService.resolve(...)}，
 * 那条回落路径<b>仍然过滤</b>，导致 {@code claw.security.dev-open-access=true} 只放开了鉴权、
 * 没放开数据范围。因此切面改为显式写入 {@link DataScopeResult#all()}，本测试即为该行为的回归锁。
 */
@ExtendWith(MockitoExtension.class)
class DataScopeAspectTest {

    @Mock
    private DataScopeService dataScopeService;

    @Mock
    private DataScope annotation;

    @InjectMocks
    private DataScopeAspect aspect;

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }

    @Test
    @DisplayName("非开发态：解析结果写入 ThreadLocal，@After 清理")
    void setsContextBeforeAndClearsAfter() {
        DataScopeResult result = new DataScopeResult(
                DataScopeResult.Scope.SELF, 1L, Set.of(), Set.of(), Set.of(), 10L);
        when(dataScopeService.resolve(any())).thenReturn(result);

        aspect.bind(mock(JoinPoint.class), annotation);
        assertSame(result, DataScopeContext.get(), "非开发态应把解析结果原样写入上下文");
        verify(dataScopeService).resolve(any());

        aspect.clear();
        assertNull(DataScopeContext.get(), "@After 必须清理 ThreadLocal，防止请求串号");
    }

    @Test
    @DisplayName("非开发态：CUSTOM 结果也应原样写入（不被篡改为 ALL）")
    void setsNonAllContextVerbatim() {
        DataScopeResult custom = new DataScopeResult(
                DataScopeResult.Scope.CUSTOM, null, Set.of(), Set.of(1001L, 1002L), Set.of(), 10L);
        when(dataScopeService.resolve(any())).thenReturn(custom);

        aspect.bind(mock(JoinPoint.class), annotation);

        DataScopeResult bound = DataScopeContext.get();
        assertSame(custom, bound, "非开发态不得把受限范围放大为 ALL");
        assertTrue(!bound.isAll(), "CUSTOM 结果的 isAll() 必须为 false");
    }

    @Test
    @DisplayName("开发态放开：必须写入 ALL 结果，且不调用解析器（决策②）")
    void writesAllResultWhenDevOpenAccess() {
        ReflectionTestUtils.setField(aspect, "devOpenAccess", true);
        try {
            aspect.bind(mock(JoinPoint.class), annotation);

            DataScopeResult bound = DataScopeContext.get();
            assertNotNull(bound, "开发态放开必须写入上下文，留 null 会让域方法回落到仍会过滤的 resolve()");
            assertTrue(bound.isAll(), "开发态放开写入的结果必须是 ALL，下游 Specification 才会退化为不过滤");
            verify(dataScopeService, never()).resolve(any());
        } finally {
            ReflectionTestUtils.setField(aspect, "devOpenAccess", false);
        }
    }

    @Test
    @DisplayName("开发态放开：@After 仍须清理，避免 ALL 泄漏到后续线程复用")
    void clearsAllResultAfterDevOpenAccess() {
        ReflectionTestUtils.setField(aspect, "devOpenAccess", true);
        try {
            aspect.bind(mock(JoinPoint.class), annotation);
            assertNotNull(DataScopeContext.get(), "前置断言：开发态已写入上下文");

            aspect.clear();

            assertNull(DataScopeContext.get(), "开发态写入的 ALL 也必须被清理，否则线程复用会放大后续请求的范围");
        } finally {
            ReflectionTestUtils.setField(aspect, "devOpenAccess", false);
        }
    }
}
