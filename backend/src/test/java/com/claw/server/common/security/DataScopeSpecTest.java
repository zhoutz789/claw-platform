package com.claw.server.common.security;

import com.claw.server.common.security.DataScopeResult.Scope;
import com.claw.server.test.scope.CriteriaProbeHarness;
import com.claw.server.test.scope.CriteriaProbeHarness.ProbeQuery;
import com.claw.server.test.scope.ScopeProbe;
import com.claw.server.test.scope.ScopeProbeUser;
import jakarta.persistence.criteria.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DataScopeSpec} 翻译规则的权威测试（权限通电 P1-T03 决策③a）。
 *
 * <p>这是「列表过滤到底过滤了什么」的唯一事实来源：把 6 个 {@link Scope} 分别翻译成
 * JPA {@code Specification}，在<b>内存 H2 上真实执行</b>，并同时断言两件事：
 * <ol>
 *   <li><b>语义</b>——究竟返回了哪几行（这才真正证明过滤生效，而非仅仅「拼出了点什么」）；</li>
 *   <li><b>SQL 标记</b>——Hibernate 真实下发的 SQL 里包含期望的列名与操作符
 *       （{@code ownerId} / {@code departmentId} / {@code orgCode} / {@code type} / {@code in} / {@code like}）。</li>
 * </ol>
 *
 * <p>覆盖两类字段映射：
 * <ul>
 *   <li><b>直带列</b>（{@link #DIRECT}）：四个维度都在实体上，逐一验证 6 个 Scope；</li>
 *   <li><b>owner 子查询</b>（{@link #VIA_OWNER}）：模拟三个试点域的真实映射
 *       （实体不带 departmentId，只有 ownerId + ownerEntity/departmentEntity），
 *       验证 DEPARTMENT / CUSTOM 退化为 {@code ownerId in (select id from ... where departmentId ...)}。</li>
 * </ul>
 */
class DataScopeSpecTest {

    /** 四维度均为实体直带列的映射。 */
    private static final DataScopeFieldMapping DIRECT =
            DataScopeFieldMapping.of("ownerId", "departmentId", "orgCode", "type");

    /** 试点域同构映射：无 departmentId / orgCode 列，DEPARTMENT、CUSTOM 须走 owner 子查询。 */
    private static final DataScopeFieldMapping VIA_OWNER =
            DataScopeFieldMapping.of("ownerId", null, null, "type",
                    ScopeProbeUser.class, ScopeProbeUser.class);

    private static CriteriaProbeHarness harness;

    @BeforeAll
    static void beforeAll() {
        harness = CriteriaProbeHarness.bootstrap();
        // 业务行：id | ownerId | departmentId | orgCode | type
        harness.seedProbes(List.of(
                new ScopeProbe(1L, 42L, 5L, "A01-01", "VEHICLE"),
                new ScopeProbe(2L, 42L, 1001L, "A01-02", "BATTERY"),
                new ScopeProbe(3L, 99L, 5L, "B02-01", "VEHICLE"),
                new ScopeProbe(4L, 99L, 1002L, "B02-02", "DRONE"),
                new ScopeProbe(5L, 7L, 2000L, "C03-01", "BATTERY")));
        // 归属人行（供 owner 子查询）：id | departmentId | orgCode
        harness.seedUsers(List.of(
                new ScopeProbeUser(42L, 5L, "A01"),
                new ScopeProbeUser(99L, 1002L, "B02"),
                new ScopeProbeUser(7L, 2000L, "C03")));
    }

    @AfterAll
    static void afterAll() {
        if (harness != null) {
            harness.close();
        }
    }

    // ---------- ALL ----------

    @Test
    @DisplayName("ALL → 空 conjunction，不生成任何过滤，全部行可见")
    void allScope_producesEmptyConjunction() {
        Predicate predicate = harness.translate(spec(DIRECT, result(Scope.ALL, null, Set.of(), Set.of(), Set.of(), null)));

        // cb.conjunction() 的 JPA 可移植定义：AND 运算符 + 零个子表达式（等价于 1=1）
        assertNotNull(predicate, "ALL 应返回 conjunction 而非 null");
        assertEquals(Predicate.BooleanOperator.AND, predicate.getOperator(), "conjunction 的运算符应为 AND");
        assertTrue(predicate.getExpressions().isEmpty(), "conjunction 不应含任何子表达式（即 1=1 不过滤）");

        ProbeQuery query = harness.execute(spec(DIRECT, DataScopeResult.all()));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), query.ids(), "ALL 必须返回全部行");
    }

    @Test
    @DisplayName("null 结果 → 同样退化为不过滤（防御式兜底）")
    void nullResult_producesEmptyConjunction() {
        ProbeQuery query = harness.execute(spec(DIRECT, null));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), query.ids(), "结果为 null 时不应过滤掉任何行");
    }

    // ---------- SELF ----------

    @Test
    @DisplayName("SELF(userId=42) → ownerId = 42，仅本人数据")
    void selfScope_filtersByOwnerIdColumn() {
        ProbeQuery query = harness.execute(
                spec(DIRECT, result(Scope.SELF, null, Set.of(), Set.of(), Set.of(), 42L)));

        assertEquals(List.of(1L, 2L), query.ids(), "SELF 应只返回 ownerId=42 的行");
        assertTrue(query.sql().contains("ownerId"), () -> "SQL 应按 ownerId 过滤，实际：" + query.sql());
    }

    @Test
    @DisplayName("SELF 缺 userId → 不过滤（避免把全表误判为空集）")
    void selfScope_withoutUserId_doesNotFilter() {
        ProbeQuery query = harness.execute(
                spec(DIRECT, result(Scope.SELF, null, Set.of(), Set.of(), Set.of(), null)));

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), query.ids(), "userId 为空时应退化为不过滤");
    }

    // ---------- DEPARTMENT ----------

    @Test
    @DisplayName("DEPARTMENT(departmentId=5) → departmentId = 5")
    void departmentScope_filtersByDepartmentIdColumn() {
        ProbeQuery query = harness.execute(
                spec(DIRECT, result(Scope.DEPARTMENT, 5L, Set.of(), Set.of(), Set.of(), 1L)));

        assertEquals(List.of(1L, 3L), query.ids(), "DEPARTMENT 应只返回 departmentId=5 的行");
        assertTrue(query.sql().contains("departmentId"),
                () -> "SQL 应按 departmentId 过滤，实际：" + query.sql());
    }

    @Test
    @DisplayName("DEPARTMENT 无 departmentId 列 → 退化为 owner 子查询（三个试点域的真实路径）")
    void departmentScope_fallsBackToOwnerSubquery() {
        ProbeQuery query = harness.execute(
                spec(VIA_OWNER, result(Scope.DEPARTMENT, 5L, Set.of(), Set.of(), Set.of(), 1L)));

        // 部门 5 内的归属人只有 42 → 其名下业务行为 1、2
        assertEquals(List.of(1L, 2L), query.ids(), "应通过 owner 子查询命中部门 5 内归属人的数据");
        assertTrue(query.sql().contains("ownerId"), () -> "SQL 外层应按 ownerId 过滤，实际：" + query.sql());
        assertTrue(query.sql().toLowerCase().contains("select"), () -> "SQL 应含子查询，实际：" + query.sql());
        assertTrue(query.sql().toLowerCase().contains(" in "), () -> "SQL 应为 IN 子查询，实际：" + query.sql());
    }

    // ---------- CUSTOM ----------

    @Test
    @DisplayName("CUSTOM(deptIds={1001,1002}) → departmentId IN (...)")
    void customScope_filtersByDepartmentIdInList() {
        ProbeQuery query = harness.execute(
                spec(DIRECT, result(Scope.CUSTOM, null, Set.of(), Set.of(1001L, 1002L), Set.of(), 1L)));

        assertEquals(List.of(2L, 4L), query.ids(), "CUSTOM 应只返回自定义部门集合内的行");
        assertTrue(query.sql().contains("departmentId"),
                () -> "SQL 应按 departmentId 过滤，实际：" + query.sql());
        assertTrue(query.sql().toLowerCase().contains(" in "), () -> "SQL 应使用 IN，实际：" + query.sql());
    }

    @Test
    @DisplayName("CUSTOM 空集合 → 不过滤（规则未配置时不应误伤）")
    void customScope_emptyDeptIds_doesNotFilter() {
        ProbeQuery query = harness.execute(
                spec(DIRECT, result(Scope.CUSTOM, null, Set.of(), Set.of(), Set.of(), 1L)));

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), query.ids(), "deptIds 为空时应退化为不过滤");
    }

    @Test
    @DisplayName("CUSTOM 无 departmentId 列 → 退化为 owner 子查询 IN")
    void customScope_fallsBackToOwnerSubquery() {
        ProbeQuery query = harness.execute(
                spec(VIA_OWNER, result(Scope.CUSTOM, null, Set.of(), Set.of(1002L, 2000L), Set.of(), 1L)));

        // 部门 1002 / 2000 内的归属人为 99、7 → 其名下业务行为 3、4、5
        assertEquals(List.of(3L, 4L, 5L), query.ids(), "应通过 owner 子查询命中自定义部门内归属人的数据");
        assertTrue(query.sql().contains("ownerId"), () -> "SQL 外层应按 ownerId 过滤，实际：" + query.sql());
        assertTrue(query.sql().toLowerCase().contains(" in "), () -> "SQL 应使用 IN 子查询，实际：" + query.sql());
    }

    // ---------- TYPE ----------

    @Test
    @DisplayName("TYPE(allowedTypes={VEHICLE}) → type IN (...)")
    void typeScope_filtersByTypeColumn() {
        ProbeQuery query = harness.execute(
                spec(DIRECT, result(Scope.TYPE, null, Set.of("VEHICLE"), Set.of(), Set.of(), 1L)));

        assertEquals(List.of(1L, 3L), query.ids(), "TYPE 应只返回 VEHICLE 类型的行");
        assertTrue(query.sql().contains("type"), () -> "SQL 应按 type 过滤，实际：" + query.sql());
    }

    @Test
    @DisplayName("TYPE 多类型 → type IN (?,?) 取并集")
    void typeScope_multipleTypes_rendersInClause() {
        ProbeQuery query = harness.execute(
                spec(DIRECT, result(Scope.TYPE, null, Set.of("VEHICLE", "DRONE"), Set.of(), Set.of(), 1L)));

        assertEquals(List.of(1L, 3L, 4L), query.ids(), "TYPE 多值应取并集");
        assertTrue(query.sql().contains("type"), () -> "SQL 应按 type 过滤，实际：" + query.sql());
        assertTrue(query.sql().toLowerCase().contains(" in "), () -> "SQL 应使用 IN，实际：" + query.sql());
    }

    @Test
    @DisplayName("TYPE 空集合 → 不过滤")
    void typeScope_emptyTypes_doesNotFilter() {
        ProbeQuery query = harness.execute(
                spec(DIRECT, result(Scope.TYPE, null, Set.of(), Set.of(), Set.of(), 1L)));

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), query.ids(), "allowedTypes 为空时应退化为不过滤");
    }

    // ---------- DEPARTMENT_AND_BELOW ----------

    @Test
    @DisplayName("DEPARTMENT_AND_BELOW(orgCodePrefixes={A01}) → orgCode LIKE 'A01%'")
    void departmentAndBelowScope_filtersByOrgCodeLike() {
        ProbeQuery query = harness.execute(
                spec(DIRECT, result(Scope.DEPARTMENT_AND_BELOW, null, Set.of(), Set.of(), Set.of("A01"), 1L)));

        assertEquals(List.of(1L, 2L), query.ids(), "本部门及以下应命中 orgCode 以 A01 开头的行");
        assertTrue(query.sql().contains("orgCode"), () -> "SQL 应按 orgCode 过滤，实际：" + query.sql());
        assertTrue(query.sql().toLowerCase().contains("like"), () -> "SQL 应使用 LIKE，实际：" + query.sql());
    }

    @Test
    @DisplayName("DEPARTMENT_AND_BELOW 多前缀 → 多个 LIKE 以 OR 相连")
    void departmentAndBelowScope_multiplePrefixes_areOred() {
        ProbeQuery query = harness.execute(
                spec(DIRECT, result(Scope.DEPARTMENT_AND_BELOW, null, Set.of(), Set.of(),
                        Set.of("A01", "C03"), 1L)));

        assertEquals(List.of(1L, 2L, 5L), query.ids(), "多前缀应取并集（OR 相连）");
        assertTrue(query.sql().toLowerCase().contains("like"), () -> "SQL 应使用 LIKE，实际：" + query.sql());
    }

    @Test
    @DisplayName("DEPARTMENT_AND_BELOW 空前缀 → 不过滤")
    void departmentAndBelowScope_emptyPrefixes_doesNotFilter() {
        ProbeQuery query = harness.execute(
                spec(DIRECT, result(Scope.DEPARTMENT_AND_BELOW, null, Set.of(), Set.of(), Set.of(), 1L)));

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), query.ids(), "orgCodePrefixes 为空时应退化为不过滤");
    }

    // ---------- 工具方法 ----------

    private static Specification<ScopeProbe> spec(DataScopeFieldMapping mapping, DataScopeResult result) {
        return DataScopeSpec.of(mapping).apply(result);
    }

    private static DataScopeResult result(Scope scope, Long departmentId, Set<String> allowedTypes,
                                         Set<Long> deptIds, Set<String> orgCodePrefixes, Long userId) {
        return new DataScopeResult(scope, departmentId, allowedTypes, deptIds, orgCodePrefixes, userId);
    }
}
