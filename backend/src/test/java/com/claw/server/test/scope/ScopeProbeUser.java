package com.claw.server.test.scope;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 数据范围测试「归属人 / 部门」探针实体（仅测试作用域，权限通电 P1-T03 决策③a）。
 *
 * <p>用途：充当 {@link com.claw.server.common.security.DataScopeFieldMapping} 的
 * {@code ownerEntity} / {@code departmentEntity}，以覆盖 {@code DataScopeSpec} 的
 * <b>子查询</b>分支——即三个试点域真正走的那条路（它们的实体不直带 departmentId，
 * 映射形如 {@code of("ownerId", null, null, "assetType", User.class, Department.class)}，
 * DEPARTMENT / CUSTOM 会退化为 {@code ownerId in (select id from users where department_id ...)}）。
 *
 * <p>因此本实体只需暴露子查询用到的三个属性：
 * <ul>
 *   <li>{@code id}           —— 子查询 select 目标；</li>
 *   <li>{@code departmentId} —— owner 子查询的过滤列；</li>
 *   <li>{@code orgCode}      —— department 子查询的前缀 LIKE 列。</li>
 * </ul>
 */
@Entity
@Table(name = "scope_probe_user")
public class ScopeProbeUser {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    /** 所属部门（owner 子查询过滤列）。 */
    @Column(name = "departmentId")
    private Long departmentId;

    /** 部门层级码（department 子查询前缀 LIKE 列）。 */
    @Column(name = "orgCode", length = 64)
    private String orgCode;

    /** JPA 规范要求的无参构造。 */
    public ScopeProbeUser() {
    }

    /**
     * 全字段构造。
     *
     * @param id           主键（显式指定）
     * @param departmentId 所属部门
     * @param orgCode      部门层级码
     */
    public ScopeProbeUser(Long id, Long departmentId, String orgCode) {
        this.id = id;
        this.departmentId = departmentId;
        this.orgCode = orgCode;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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
}
