package com.claw.server.test.scope;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 数据范围测试探针实体（仅测试作用域，权限通电 P1-T03 决策③a）。
 *
 * <p>刻意做成「四个维度列一次性齐备」的最小实体，让
 * {@link com.claw.server.common.security.DataScopeSpec} 的 6 个 Scope 分支都能落到
 * <b>实体直带列</b>路径上，从而在一张表上把全部翻译规则真实执行一遍：
 * <ul>
 *   <li>{@code ownerId}      —— SELF；</li>
 *   <li>{@code departmentId} —— DEPARTMENT / CUSTOM；</li>
 *   <li>{@code orgCode}      —— DEPARTMENT_AND_BELOW；</li>
 *   <li>{@code type}         —— TYPE。</li>
 * </ul>
 *
 * <p>列名与业务实体解耦，直接沿用属性名（{@code ownerId}/{@code departmentId}/{@code orgCode}/
 * {@code type}），使断言可以在生成的 SQL 里直接匹配这些标记。
 *
 * <p>主键不用 {@code @GeneratedValue}，由用例显式指定，便于对「返回了哪几行」做精确断言。
 *
 * <p><b>不会污染生产：</b>该类位于 {@code src/test/java}，且主配置 {@code ddl-auto: none}
 * （表结构由 Flyway 全权管理），集成测试不会为它建表或做校验。ArchUnit 亦以
 * {@code DO_NOT_INCLUDE_TESTS} 导入，边界规则不受影响。
 */
@Entity
@Table(name = "scope_probe")
public class ScopeProbe {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    /** 归属人列（SELF 维度）。 */
    @Column(name = "ownerId")
    private Long ownerId;

    /** 部门列（DEPARTMENT / CUSTOM 维度）。 */
    @Column(name = "departmentId")
    private Long departmentId;

    /** 层级码列（DEPARTMENT_AND_BELOW 维度，前缀 LIKE）。 */
    @Column(name = "orgCode", length = 64)
    private String orgCode;

    /** 类型列（TYPE 维度）。 */
    @Column(name = "type", length = 32)
    private String type;

    /** JPA 规范要求的无参构造。 */
    public ScopeProbe() {
    }

    /**
     * 全字段构造，便于用例以一行表达一条种子数据。
     *
     * @param id           主键（显式指定）
     * @param ownerId      归属人
     * @param departmentId 部门
     * @param orgCode      层级码
     * @param type         类型
     */
    public ScopeProbe(Long id, Long ownerId, Long departmentId, String orgCode, String type) {
        this.id = id;
        this.ownerId = ownerId;
        this.departmentId = departmentId;
        this.orgCode = orgCode;
        this.type = type;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }

    public String getOrgCode() {
        return orgCode;
    }

    public void setOrgCode(String orgCode) {
        this.orgCode = orgCode;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
