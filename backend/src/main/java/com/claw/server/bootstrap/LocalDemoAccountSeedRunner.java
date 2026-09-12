package com.claw.server.bootstrap;

import com.claw.server.common.enums.RoleSource;
import com.claw.server.domain.role.*;
import com.claw.server.domain.user.User;
import com.claw.server.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 本地 H2 演示账号种子（仅 local profile 执行，@Order(200) 晚于 LocalV3PermissionSeedRunner）。
 *
 * <p>v3 角色已落到三层权限（LocalV3PermissionSeedRunner 播入 6 个 v3 角色 + 权限），但
 * claw.users / claw.user_role_packages 此前无任何种子（库里根本没有可登录用户）。
 * 本 runner 为 7+1 类角色各建一个可登录演示账号，用于本地验收三层权限：
 *   ① 菜单按角色过滤 ② 接口按权限拦截 ③ 数据范围只返回本人数据。
 *
 * <p>登录方式（短信验证码，dev 回显，无需密码）：
 *   1) POST /api/v1/auth/sms-code {"phone":"13800000001"} → 取回显的 6 位码
 *   2) POST /api/v1/auth/login    {"phone":"13800000001","code":"<码>"} → 取 token
 *   3) GET  /api/v1/auth/me (Bearer token) → roles + permissions 按角色回显
 *
 * <p>本类同时补建 CUSTOMER / PLATFORM_ADMIN 两个角色行（LocalV3PermissionSeedRunner 只播 6 个 v3 角色），
 * 并把 PLATFORM_ADMIN.grants 置为通配 ["*"]，使管理员演示账号具备全量权限。
 * 仅 local H2 复刻，不进 Flyway；与 V70（生产/真实 PG）共享同一套演示账号定义。幂等：存在性检查后写入。
 */
@Component
@Profile("local")
@Order(200)
@RequiredArgsConstructor
@Slf4j
public class LocalDemoAccountSeedRunner implements org.springframework.boot.CommandLineRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRolePackageRepository userRolePackageRepository;
    private final UserRoleRepository userRoleRepository;

    /** phone → roleCode（演示账号矩阵，与 V70 一致）。 */
    private static final List<Map.Entry<String, String>> DEMO = List.of(
            Map.entry("13800000001", "DRIVER"),
            Map.entry("13800000002", "PILOT"),
            Map.entry("13800000003", "MERCHANT"),
            Map.entry("13800000004", "MANUFACTURER"),
            Map.entry("13800000005", "STATION"),
            Map.entry("13800000006", "REGULATOR"),
            Map.entry("13800000007", "PLATFORM_ADMIN"),
            Map.entry("13800000008", "CUSTOMER"));

    @Override
    public void run(String... args) {
        log.info("[local-seed] 开始播入演示账号（仅 local 环境）");

        // 1) 补建 CUSTOMER / PLATFORM_ADMIN / ASSET_OWNER 角色行（local H2 缺省不存在）
        //    ASSET_OWNER：V11 把旧 OWNER 重命名为 ASSET_OWNER；资产建档 grantOwnership 引用此码，
        //    缺则 createVehicle 在本地 H2 同样 40401。grants 与 V11 迁移保持一致。
        ensureRole("CUSTOMER", "role.customer.name", "SELF", "[]");
        ensureRole("PLATFORM_ADMIN", "role.platform_admin.name", "ALL", "[\"*\"]");
        ensureRole("ASSET_OWNER", "role.asset_owner.name", "SELF",
                "[\"PURCHASE_ASSET\",\"LIST_IN_SHARED_POOL\",\"SET_RENTAL_SHARE\",\"VIEW_REVENUE_SHARE\","
                        + "\"REQUEST_RECOVERY\",\"TRADE_IN\",\"VIEW_ASSET_STATUS\",\"VIEW_ASSET_SOH\","
                        + "\"VIEW_CUSTODY_CHAIN\",\"SET_USAGE_FEE\"]");

        // 2) 为每个演示账号建 user + 角色包（驱动 JWT roles 声明 + effectivePermissions）
        int ok = 0;
        for (Map.Entry<String, String> e : DEMO) {
            String phone = e.getKey();
            String roleCode = e.getValue();

            User user = userRepository.findByPhone(phone)
                    .orElseGet(() -> userRepository.save(User.builder().phone(phone).build()));

            Role role = roleRepository.findByCode(roleCode).orElse(null);
            if (role == null) {
                log.warn("[local-seed] 角色 {} 不存在（权限种子可能未先执行），跳过账号 {}", roleCode, phone);
                continue;
            }

            if (userRolePackageRepository.findByUserIdAndRoleId(user.getId(), role.getId()).isEmpty()) {
                userRolePackageRepository.save(UserRolePackage.builder()
                        .userId(user.getId()).roleId(role.getId()).source(RoleSource.ADMIN).build());
            }

            // PLATFORM_ADMIN 额外补 user_roles（isPlatformAdmin 读取此表）
            if ("PLATFORM_ADMIN".equals(roleCode)
                    && !userRoleRepository.existsByUserIdAndRoleId(user.getId(), role.getId())) {
                userRoleRepository.save(UserRole.builder()
                        .userId(user.getId()).roleId(role.getId()).tenantId(1L).build());
            }
            ok++;
        }

        // 3) 兜底确保 PLATFORM_ADMIN 持通配权限
        roleRepository.findByCode("PLATFORM_ADMIN").ifPresent(r -> {
            if (r.getGrants() == null || !r.getGrants().contains("*")) {
                r.setGrants("[\"*\"]");
                roleRepository.save(r);
            }
        });

        log.info("[local-seed] 演示账号播入完成（{} 个，覆盖 7 类业务角色 + 普通用户）", ok);
    }

    private void ensureRole(String code, String nameI18n, String dataScope, String grants) {
        roleRepository.findByCode(code).ifPresentOrElse(role -> {
            if (!grants.equals(role.getGrants())) {
                role.setGrants(grants);
                role.setDataScope(dataScope);
                roleRepository.save(role);
            }
        }, () -> roleRepository.save(Role.builder()
                .code(code).nameI18n(nameI18n).grants(grants).autoGrant(false)
                .status("ACTIVE").dataScope(dataScope).dataScopeTypes("[]").dataRuleIds("").build()));
    }
}
