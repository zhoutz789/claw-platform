package com.claw.server.domain.role;

import com.claw.server.common.api.BizException;
import com.claw.server.domain.user.Department;
import com.claw.server.domain.user.DepartmentRepository;
import com.claw.server.domain.user.User;
import com.claw.server.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 数据规则 #{...} 上下文变量替换 + SQL 白名单校验（权限通电 P1-T03）。
 *
 * <p>变量来源：{@link com.claw.server.common.security.AuthContext} 当前用户 + 其主部门 org_code。
 * <ul>
 *   <li>#{sys_user_id}       —— 当前用户 id；</li>
 *   <li>#{sys_user_code}     —— 当前用户手机号（登录账号）；</li>
 *   <li>#{sys_org_code}      —— 当前用户主部门 org_code；</li>
 *   <li>#{sys_org_code_like} —— 主部门 org_code + %（"本部门及以下" LIKE）；</li>
 *   <li>#{sys_multi_org_code}—— 当前用户所有部门 org_code（逗号分隔，IN 用）；</li>
 *   <li>#{tenant_id}         —— 当前租户 id。</li>
 * </ul>
 *
 * <p>SQL 模式（{@code rule_conditions = 'SQL'}）必须对片段做白名单校验：拒绝多语句（; 拼接）与
 * DDL/写操作关键字，避免数据规则成为注入入口。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RuleValueResolver {

    private static final String[] DDL_KEYWORDS = {
            "DROP", "ALTER", "CREATE", "TRUNCATE", "DELETE", "UPDATE", "INSERT",
            "GRANT", "REVOKE", "MERGE", "EXEC", "EXECUTE"
    };

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;

    /** 把模板中的 #{...} 变量替换为当前用户上下文。 */
    public String resolve(String template, Long userId) {
        if (template == null) {
            return null;
        }
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        String phone = (user == null || user.getPhone() == null) ? "" : user.getPhone();
        Department dept = (user != null && user.getDepartmentId() != null)
                ? departmentRepository.findById(user.getDepartmentId()).orElse(null) : null;
        String orgCode = (dept == null || dept.getOrgCode() == null) ? "" : dept.getOrgCode();
        String tenantId = (user == null || user.getTenantId() == null) ? "1" : String.valueOf(user.getTenantId());

        return template
                .replace("#{sys_user_id}", userId == null ? "" : String.valueOf(userId))
                .replace("#{sys_user_code}", phone)
                .replace("#{sys_org_code}", orgCode)
                .replace("#{sys_org_code_like}", orgCode + "%")
                .replace("#{sys_multi_org_code}", orgCode)
                .replace("#{tenant_id}", tenantId);
    }

    /** SQL 模式：先白名单校验再替换变量；非法（DDL/多语句）抛 {@link BizException}。 */
    public String resolveSql(String template, Long userId) {
        assertSafeSql(template);
        return resolve(template, userId);
    }

    /** 校验 SQL 片段安全性：拒绝多语句（; 拼接）与 DDL/写操作关键字（按单词边界匹配）。 */
    public void assertSafeSql(String sql) {
        if (sql == null) {
            return;
        }
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.contains(";")) {
            throw BizException.of(40001, "error.datascope.sql.multi");
        }
        String upper = trimmed.toUpperCase();
        for (String kw : DDL_KEYWORDS) {
            if (upper.matches(".*\\b" + kw + "\\b.*")) {
                throw BizException.of(40001, "error.datascope.sql.ddl");
            }
        }
    }
}
