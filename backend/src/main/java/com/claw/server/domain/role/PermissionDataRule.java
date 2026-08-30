package com.claw.server.domain.role;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 数据权限规则（对应 claw.permission_data_rules，V44 新增）。
 *
 * <p>借鉴 JeecgBoot {@code sys_permission_data_rule}：把"改一次数据范围要改代码"的配置化。
 * 通过 {@code rule_column}/{@code rule_conditions}/{@code rule_value} 描述一条可注入的过滤规则，
 * {@code rule_value} 中支持 #{sys_user_id} 等上下文变量（由 {@link RuleValueResolver} 运行时替换）。
 *
 * <p>{@code rule_conditions} 取值：{@code =} / {@code IN} / {@code LIKE} / {@code SQL}（SQL 模式须白名单校验）。
 */
@Entity
@Table(name = "permission_data_rules", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PermissionDataRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 绑定的权限点 code（对应 permissions.code）。 */
    @Column(nullable = false)
    private String permissionCode;

    /** 规则名（同权限点内唯一，作为种子幂等键）。 */
    @Column(nullable = false)
    private String ruleName;

    /** 作用列名（如 department_id / asset_type / owner_id）。 */
    @Column(nullable = false)
    private String ruleColumn;

    /** 条件：= / IN / LIKE / SQL。 */
    @Column(nullable = false)
    private String ruleConditions;

    /** 规则值（支持 #{sys_user_id} 等变量；SQL 模式为片段）。 */
    @Column(columnDefinition = "text")
    private String ruleValue;

    @Column(nullable = false)
    @Builder.Default
    private String status = "ENABLED";

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
