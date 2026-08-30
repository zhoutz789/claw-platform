package com.claw.server.common.security;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 数据范围 → JPA {@link Specification} 翻译器（权限通电 P1-T03）。
 *
 * <p>纯工具，仅依赖 common.security.{@link DataScopeResult} 与 JPA Criteria API，
 * <b>不反向依赖任何 domain 包</b>，满足 ArchUnit「common 不得依赖 domain」边界。
 * 子查询所需的目标实体类由 {@link DataScopeFieldMapping} 在运行时从 domain 调用方传入
 * （{@code ownerEntity}/{@code departmentEntity}），common 层本身不 import 任何实体类。
 *
 * <p>谓词规则（字段名由 {@link DataScopeFieldMapping} 注入）：
 * <ul>
 *   <li>{@code ALL}                → 无谓词（conjunction）；</li>
 *   <li>{@code SELF}               → {@code ownerId = :userId}；</li>
 *   <li>{@code TYPE}               → {@code type IN (:allowedTypes)}；</li>
 *   <li>{@code DEPARTMENT}         → {@code departmentId = :departmentId}（无列则 owner 子查询 users.department_id）；</li>
 *   <li>{@code DEPARTMENT_AND_BELOW}→ {@code orgCode LIKE :prefix%}（无列则 department 子查询 org_code LIKE）；</li>
 *   <li>{@code CUSTOM}             → {@code departmentId IN (:deptIds)}（无列则 owner 子查询 users.department_id IN）。</li>
 * </ul>
 */
public final class DataScopeSpec {

    private final String ownerId;
    private final String departmentId;
    private final String orgCode;
    private final String type;
    private final Class<?> ownerEntity;
    private final Class<?> departmentEntity;

    private DataScopeSpec(String ownerId, String departmentId, String orgCode, String type,
                          Class<?> ownerEntity, Class<?> departmentEntity) {
        this.ownerId = ownerId;
        this.departmentId = departmentId;
        this.orgCode = orgCode;
        this.type = type;
        this.ownerEntity = ownerEntity;
        this.departmentEntity = departmentEntity;
    }

    /** 由字段映射构造。 */
    public static DataScopeSpec of(DataScopeFieldMapping mapping) {
        return new DataScopeSpec(mapping.ownerId(), mapping.departmentId(), mapping.orgCode(), mapping.type(),
                mapping.ownerEntity(), mapping.departmentEntity());
    }

    /** 位置参数：[ownerId, departmentId, orgCode, type]，子查询目标类留空（仅支持实体直带列维度）。 */
    public static DataScopeSpec of(String... columns) {
        String owner = columns != null && columns.length > 0 ? columns[0] : null;
        String dept = columns != null && columns.length > 1 ? columns[1] : null;
        String code = columns != null && columns.length > 2 ? columns[2] : null;
        String typ = columns != null && columns.length > 3 ? columns[3] : null;
        return new DataScopeSpec(owner, dept, code, typ, null, null);
    }

    /** 把数据范围结果翻译为 JPA Specification。 */
    public <T> Specification<T> apply(DataScopeResult result) {
        return (Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb) -> {
            if (result == null || result.scope() == DataScopeResult.Scope.ALL) {
                return cb.conjunction();
            }
            return switch (result.scope()) {
                case SELF -> selfPredicate(root, cb, result);
                case TYPE -> typePredicate(root, cb, result);
                case DEPARTMENT -> departmentPredicate(root, query, cb, result);
                case DEPARTMENT_AND_BELOW -> belowPredicate(root, query, cb, result);
                case CUSTOM -> customPredicate(root, query, cb, result);
                case ALL -> cb.conjunction();
            };
        };
    }

    private <T> Predicate selfPredicate(Root<T> root, CriteriaBuilder cb, DataScopeResult r) {
        if (ownerId == null || r.userId() == null) {
            return cb.conjunction();
        }
        return cb.equal(root.get(ownerId), r.userId());
    }

    private <T> Predicate typePredicate(Root<T> root, CriteriaBuilder cb, DataScopeResult r) {
        if (type == null || r.allowedTypes() == null || r.allowedTypes().isEmpty()) {
            return cb.conjunction();
        }
        return root.get(type).in(r.allowedTypes());
    }

    private <T> Predicate departmentPredicate(Root<T> root, CriteriaQuery<?> q, CriteriaBuilder cb, DataScopeResult r) {
        if (departmentId != null) {
            return cb.equal(root.get(departmentId), r.departmentId());
        }
        if (ownerId != null && ownerEntity != null) {
            Subquery<Long> sq = q.subquery(Long.class);
            Root<?> u = sq.from(ownerEntity);
            sq.select(u.get("id").as(Long.class));
            sq.where(cb.equal(u.get("departmentId"), r.departmentId()));
            return root.get(ownerId).in(sq);
        }
        return cb.conjunction();
    }

    private <T> Predicate belowPredicate(Root<T> root, CriteriaQuery<?> q, CriteriaBuilder cb, DataScopeResult r) {
        List<String> prefixes = new ArrayList<>(r.orgCodePrefixes() == null ? Set.of() : r.orgCodePrefixes());
        if (prefixes.isEmpty()) {
            return cb.conjunction();
        }
        if (orgCode != null) {
            Predicate p = cb.disjunction();
            for (String pfx : prefixes) {
                p = cb.or(p, cb.like(root.get(orgCode).as(String.class), pfx + "%"));
            }
            return p;
        }
        if (departmentId != null && departmentEntity != null) {
            Subquery<Long> sq = q.subquery(Long.class);
            Root<?> d = sq.from(departmentEntity);
            sq.select(d.get("id").as(Long.class));
            sq.where(likeOrgCode(d, cb, prefixes));
            return root.get(departmentId).in(sq);
        }
        if (ownerId != null && ownerEntity != null && departmentEntity != null) {
            Subquery<Long> sq = q.subquery(Long.class);
            Root<?> u = sq.from(ownerEntity);
            Subquery<Long> dsq = sq.subquery(Long.class);
            Root<?> d = dsq.from(departmentEntity);
            dsq.select(d.get("id").as(Long.class));
            dsq.where(likeOrgCode(d, cb, prefixes));
            sq.select(u.get("id").as(Long.class));
            sq.where(u.get("departmentId").in(dsq));
            return root.get(ownerId).in(sq);
        }
        return cb.conjunction();
    }

    private <T> Predicate customPredicate(Root<T> root, CriteriaQuery<?> q, CriteriaBuilder cb, DataScopeResult r) {
        if (r.deptIds() == null || r.deptIds().isEmpty()) {
            return cb.conjunction();
        }
        if (departmentId != null) {
            return root.get(departmentId).in(r.deptIds());
        }
        if (ownerId != null && ownerEntity != null) {
            Subquery<Long> sq = q.subquery(Long.class);
            Root<?> u = sq.from(ownerEntity);
            sq.select(u.get("id").as(Long.class));
            sq.where(u.get("departmentId").in(r.deptIds()));
            return root.get(ownerId).in(sq);
        }
        return cb.conjunction();
    }

    private static Predicate likeOrgCode(Root<?> deptRoot, CriteriaBuilder cb, List<String> prefixes) {
        Predicate p = cb.disjunction();
        for (String pfx : prefixes) {
            p = cb.or(p, cb.like(deptRoot.get("orgCode").as(String.class), pfx + "%"));
        }
        return p;
    }
}
