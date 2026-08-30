package com.claw.server.common.security;

import com.claw.server.common.security.DataScopeResult.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DataScopeResult} 单元测试（权限通电 P1-T03 决策②）。
 *
 * <p>锁定新增的 {@link DataScopeResult#all()} 工厂语义：开发态放开时切面会把它写入
 * {@link DataScopeContext}，故它必须是一个「纯净的 ALL」——scope 为 ALL、各集合维度为空、
 * 各标量维度为 null，任何一处带值都可能让下游 {@link DataScopeSpec} 生成意外谓词。
 */
class DataScopeResultTest {

    @Test
    @DisplayName("all() 应产出 scope=ALL 且 isAll() 为真")
    void allFactory_isAllScope() {
        DataScopeResult result = DataScopeResult.all();

        assertNotNull(result, "all() 不应返回 null");
        assertEquals(Scope.ALL, result.scope(), "all() 的 scope 必须是 ALL");
        assertTrue(result.isAll(), "all() 的 isAll() 必须为 true");
        assertFalse(result.isSelfOnly(), "ALL 不是 SELF，isSelfOnly() 必须为 false");
    }

    @Test
    @DisplayName("all() 的其余维度应为空集合 / null，避免下游误生成谓词")
    void allFactory_otherDimensionsAreEmpty() {
        DataScopeResult result = DataScopeResult.all();

        assertNull(result.departmentId(), "ALL 不应携带 departmentId");
        assertNull(result.userId(), "ALL 不应携带 userId");
        assertNotNull(result.allowedTypes(), "allowedTypes 应为空集合而非 null");
        assertNotNull(result.deptIds(), "deptIds 应为空集合而非 null");
        assertNotNull(result.orgCodePrefixes(), "orgCodePrefixes 应为空集合而非 null");
        assertTrue(result.allowedTypes().isEmpty(), "ALL 的 allowedTypes 必须为空");
        assertTrue(result.deptIds().isEmpty(), "ALL 的 deptIds 必须为空");
        assertTrue(result.orgCodePrefixes().isEmpty(), "ALL 的 orgCodePrefixes 必须为空");
    }

    @Test
    @DisplayName("all() 每次返回等值结果（record 值语义），可安全反复调用")
    void allFactory_isValueEqual() {
        assertEquals(DataScopeResult.all(), DataScopeResult.all(), "record 值语义下两次 all() 应相等");
        assertEquals(new DataScopeResult(Scope.ALL, null, Set.of(), Set.of(), Set.of(), null),
                DataScopeResult.all(),
                "all() 应等价于显式构造的纯净 ALL");
    }

    @Test
    @DisplayName("非 ALL 结果的 isAll() 必须为假（防止误判放开）")
    void nonAllResult_isNotAll() {
        DataScopeResult self = new DataScopeResult(Scope.SELF, null, Set.of(), Set.of(), Set.of(), 42L);
        assertFalse(self.isAll(), "SELF 不应被判为 ALL");
        assertTrue(self.isSelfOnly(), "SELF 的 isSelfOnly() 应为 true");

        DataScopeResult custom = new DataScopeResult(Scope.CUSTOM, null, Set.of(), Set.of(1001L), Set.of(), 7L);
        assertFalse(custom.isAll(), "CUSTOM 不应被判为 ALL");
        assertFalse(custom.isSelfOnly(), "CUSTOM 的 isSelfOnly() 应为 false");
    }
}
