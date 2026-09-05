package com.claw.server.bootstrap;

import com.claw.server.domain.role.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 本地 H2 权限种子（仅 local profile 执行）。
 *
 * <p>本地 H2 模式关闭了 Flyway（application-local.yml: flyway.enabled=false），不跑任何 V 脚本，
 * 因此无任何角色/权限/菜单种子，除 dev-open-access 旁路外全部 403，无法体验"权限通电"后的真实效果。
 * 本 runner 在本地环境把 v3 六角色（司机/飞手/商家/厂家/服务站/监管者）及其三层权限（角色/功能权限/数据范围）
 * 自包含地播入 H2，使本地关掉 dev-open-access 也能看到真实权限效果，便于周老板本地验收"通电"。
 *
 * <p>与 V69（Flyway，生产/真实 PG 环境）共享同一套权限矩阵定义；本类仅是把该定义用 JPA 在本地复刻一遍，
 * 不进 Flyway、不影响 PG 种子。全量幂等：逐条存在性检查后写入。
 *
 * <p>注意：本类只在 {@code local} profile 下被 Spring 实例化（@Profile），生产/PG 环境不会加载。
 */
@Component
@Profile("local")
@Order(100)
@RequiredArgsConstructor
@Slf4j
public class LocalV3PermissionSeedRunner implements org.springframework.boot.CommandLineRunner {

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;
    private final RoleTemplateRepository roleTemplateRepository;
    private final RoleTemplatePermissionRepository roleTemplatePermissionRepository;
    private final ObjectMapper objectMapper;

    /** v3 六角色 → 权限码清单（与 V69 迁移保持一致）。 */
    private static final Map<String, RoleDef> ROLES = new LinkedHashMap<>();
    static {
        ROLES.put("DRIVER", new RoleDef("role.driver.name", "SELF", List.of(
                "menu:workbench", "menu:assets", "menu:asset-trace", "menu:task-logi", "menu:task-rent",
                "menu:onboarding-apply", "asset:create", "asset:update", "asset:delete", "asset:export",
                "order:view", "order:fulfill:pay")));
        ROLES.put("PILOT", new RoleDef("role.pilot.name", "SELF", List.of(
                "menu:workbench", "menu:airspace-zones", "menu:flight-plans", "menu:pilot-licenses",
                "menu:drone-ops", "menu:task-drone", "menu:onboarding-apply",
                "asset:create", "asset:update", "asset:delete", "asset:export", "order:view")));
        ROLES.put("MERCHANT", new RoleDef("role.merchant.name", "SELF", List.of(
                "menu:workbench", "menu:goods-list", "menu:product-wizard", "menu:product-publish",
                "menu:order-manage", "menu:merchants", "menu:task-ad", "menu:onboarding-apply",
                "product:create", "product:update", "product:delete", "product:export",
                "order:create", "order:update", "order:delete", "order:export",
                "order:view", "order:fulfill:pay")));
        ROLES.put("MANUFACTURER", new RoleDef("role.manufacturer.name", "CUSTOM", List.of(
                "menu:workbench", "menu:product-center", "menu:certificate", "menu:bind-ownership",
                "menu:product-template", "menu:data-binding", "menu:authorization", "menu:manufacturer",
                "menu:production", "menu:mfg-inventory", "menu:station-consignment", "menu:transfers",
                "menu:fulfillment-orders", "menu:drone-ops", "menu:onboarding-apply",
                "mfg:production:create", "mfg:production:view", "mfg:inventory:view", "mfg:inventory:own",
                "mfg:inventory:consignment:view", "mfg:certificate:view", "mfg:certificate:print",
                "mfg:transfer:create", "mfg:fulfill:ship", "mfg:recovery:create",
                "product:create", "product:update", "product:delete", "product:export",
                "asset:create", "asset:update", "asset:delete", "asset:export", "order:view")));
        ROLES.put("STATION", new RoleDef("role.station.name", "CUSTOM", List.of(
                "menu:workbench", "menu:onboarding-apply",
                "station:consignment:view", "station:consignment:receive", "station:transfer:handover",
                "station:transfer:receive", "order:fulfill:confirm", "station:fulfill:receive",
                "order:pickup:scan", "station:pickup:confirm", "station:recovery:confirm",
                "order:view", "mfg:inventory:view")));
        ROLES.put("REGULATOR", new RoleDef("role.regulator.name", "ALL", List.of(
                "menu:workbench", "menu:airspace-zones", "menu:flight-plans", "menu:pilot-licenses",
                "menu:drone-ops", "menu:risk", "menu:alerts", "menu:insurance", "menu:arbitration",
                "menu:complaints", "menu:assets", "menu:asset-trace", "menu:custody", "menu:shared-pool",
                "menu:recovery", "menu:task-drone", "menu:task-logi", "menu:task-rent", "menu:task-ad",
                "menu:task-video", "menu:task-near", "menu:onboarding-apply",
                "order:view", "mfg:production:view", "mfg:inventory:view", "mfg:certificate:view",
                "station:consignment:view")));
    }

    /** 需要播种的权限点（菜单分组 + 菜单叶子 + 功能按钮）。本地 H2 为空，需全量补。 */
    private static final List<PermDef> PERMS = new ArrayList<>();
    static {
        // 菜单分组
        g("menu:prod", "产品管理", null);
        g("menu:project", "项目管理", null);
        g("menu:goods", "商品管理", null);
        g("menu:task", "任务发布", null);
        g("menu:ops", "运营管理", null);
        g("menu:supply", "供应流通", null);
        g("menu:station", "服务站", null);
        g("menu:drone", "无人机", null);
        g("menu:risk-center", "风控合规", null);
        g("menu:onboarding", "入驻管理", null);
        // 菜单叶子
        m("menu:workbench", "工作台", null, "/workbench", 10);
        m("menu:product-center", "产品中心", "menu:prod", "/product-center", 21);
        m("menu:certificate", "合格证", "menu:prod", "/certificate", 22);
        m("menu:bind-ownership", "产权绑定", "menu:prod", "/bind-ownership", 23);
        m("menu:product-template", "产品模板", "menu:prod", "/product-template", 24);
        m("menu:data-binding", "设备数据接入", "menu:prod", "/device-data-access", 25);
        m("menu:authorization", "授权管理", "menu:prod", "/authorization", 26);
        m("menu:goods-list", "商品列表", "menu:goods", "/goods-list", 41);
        m("menu:product-wizard", "商品向导", "menu:goods", "/product-wizard", 42);
        m("menu:product-publish", "商品发布", "menu:goods", "/product-publish", 46);
        m("menu:order-manage", "订单管理", "menu:goods", "/order-manage", 45);
        m("menu:merchants", "商家管理", "menu:goods", "/merchants", 47);
        m("menu:task-drone", "无人机任务", "menu:task", "/task-drone", 51);
        m("menu:task-rent", "租赁任务", "menu:task", "/task-rent", 52);
        m("menu:task-logi", "物流任务", "menu:task", "/task-logi", 53);
        m("menu:task-ad", "广告任务", "menu:task", "/task-ad", 54);
        m("menu:task-video", "视频任务", "menu:task", "/task-video", 55);
        m("menu:task-near", "附近任务", "menu:task", "/task-near", 56);
        m("menu:assets", "资产管理", "menu:ops", "/assets", 64);
        m("menu:asset-trace", "资产溯源", "menu:ops", "/asset-trace", 65);
        m("menu:custody", "产权链", "menu:ops", "/custody", 66);
        m("menu:shared-pool", "共享池", "menu:ops", "/shared-pool", 68);
        m("menu:recovery", "回收处置", "menu:ops", "/recovery", 69);
        m("menu:inventory-overview", "库存总览", "menu:supply", "/inventory-overview", 450);
        m("menu:production", "生产管理", "menu:supply", "/production", 71);
        m("menu:mfg-inventory", "厂家库存", "menu:supply", "/mfg-inventory", 72);
        m("menu:station-consignment", "寄售库存", "menu:supply", "/station-consignment", 73);
        m("menu:transfers", "调拨管理", "menu:supply", "/transfers", 74);
        m("menu:fulfillment-orders", "履约订单", "menu:supply", "/fulfillment-orders", 75);
        m("menu:station-inventory", "服务站库存", "menu:station", "/station-inventory", 1);
        m("menu:station-projects", "服务站项目", "menu:station", "/station-projects", 2);
        m("menu:station-settlements", "服务站结算", "menu:station", "/station-settlements", 3);
        m("menu:airspace-zones", "空域分区", "menu:drone", "/airspace-zones", 91);
        m("menu:flight-plans", "飞行计划", "menu:drone", "/flight-plans", 92);
        m("menu:pilot-licenses", "飞手资质", "menu:drone", "/pilot-licenses", 93);
        m("menu:drone-ops", "无人机作业", "menu:drone", "/drone-ops", 94);
        m("menu:risk", "风控监控", "menu:risk-center", "/risk", 81);
        m("menu:alerts", "异常告警", "menu:risk-center", "/alerts", 82);
        m("menu:insurance", "保险", "menu:risk-center", "/insurance", 83);
        m("menu:arbitration", "争议仲裁", "menu:risk-center", "/arbitration", 84);
        m("menu:complaints", "投诉", "menu:risk-center", "/complaints", 85);
        m("menu:onboarding-apply", "入驻申请", "menu:onboarding", "/onboarding-apply", 1);
        // 功能按钮（资源:动作 + 语义化点）
        b("asset:create"); b("asset:update"); b("asset:delete"); b("asset:export");
        b("product:create"); b("product:update"); b("product:delete"); b("product:export");
        b("order:create"); b("order:update"); b("order:delete"); b("order:export");
        b("order:view"); b("order:fulfill:pay");
        b("mfg:production:create"); b("mfg:production:view");
        b("mfg:inventory:view"); b("mfg:inventory:own"); b("mfg:inventory:consignment:view");
        b("mfg:certificate:view"); b("mfg:certificate:print");
        b("mfg:transfer:create"); b("mfg:fulfill:ship"); b("mfg:recovery:create");
        b("station:consignment:view"); b("station:consignment:receive");
        b("station:transfer:handover"); b("station:transfer:receive");
        b("order:fulfill:confirm"); b("station:fulfill:receive");
        b("order:pickup:scan"); b("station:pickup:confirm"); b("station:recovery:confirm");
    }

    private static void g(String code, String name, String parent) { PERMS.add(new PermDef(code, name, "MENU", parent, null, 0)); }
    private static void m(String code, String name, String parent, String path, int sort) { PERMS.add(new PermDef(code, name, "MENU", parent, path, sort)); }
    private static void b(String code) { PERMS.add(new PermDef(code, code, "BUTTON", null, null, 0)); }

    @Override
    public void run(String... args) {
        log.info("[local-seed] 开始播入 v3 权限种子（仅 local 环境）");
        // 1) 权限点
        for (PermDef p : PERMS) {
            if (permissionRepository.findByCode(p.code).isEmpty()) {
                permissionRepository.save(Permission.builder()
                        .code(p.code).name(p.name).ptype(p.ptype)
                        .parentCode(p.parent).path(p.path).sortNo(p.sort).build());
            }
        }
        // 2) 角色模板
        for (String code : ROLES.keySet()) {
            if (roleTemplateRepository.findByCode(code).isEmpty()) {
                RoleDef d = ROLES.get(code);
                roleTemplateRepository.save(RoleTemplate.builder()
                        .code(code).name(d.nameI18n).principalType(code)
                        .description("v3 本地种子角色模板").build());
            }
        }
        // 3) 角色
        for (Map.Entry<String, RoleDef> e : ROLES.entrySet()) {
            String code = e.getKey();
            RoleDef d = e.getValue();
            roleRepository.findByCode(code).ifPresentOrElse(role -> {
                role.setStatus("ACTIVE");
                role.setDataScope(d.dataScope);
                roleRepository.save(role);
            }, () -> roleRepository.save(Role.builder()
                    .code(code).nameI18n(d.nameI18n).grants("[]").autoGrant(false)
                    .status("ACTIVE").dataScope(d.dataScope).dataScopeTypes("[]").dataRuleIds("").build()));
        }
        // 4) 角色模板 ↔ 权限挂载
        for (Map.Entry<String, RoleDef> e : ROLES.entrySet()) {
            String code = e.getKey();
            for (String perm : e.getValue().perms) {
                RoleTemplatePermission.Key key = new RoleTemplatePermission.Key(code, perm);
                if (!roleTemplatePermissionRepository.existsById(key)) {
                    roleTemplatePermissionRepository.save(RoleTemplatePermission.builder()
                            .templateCode(code).permissionCode(perm).build());
                }
            }
        }
        // 5) 回写 roles.grants（与 V69 to_jsonb(array_agg) 等价：JSON 数组字符串）
        for (Map.Entry<String, RoleDef> e : ROLES.entrySet()) {
            String code = e.getKey();
            List<String> grants = new ArrayList<>(e.getValue().perms);
            try {
                String json = objectMapper.writeValueAsString(grants);
                roleRepository.findByCode(code).ifPresent(role -> {
                    role.setGrants(json);
                    roleRepository.save(role);
                });
            } catch (JsonProcessingException ex) {
                log.warn("[local-seed] 序列化 {} grants 失败: {}", code, ex.getMessage());
            }
        }
        log.info("[local-seed] v3 权限种子播入完成（{} 角色 / {} 权限点）", ROLES.size(), PERMS.size());
    }

    private static final class RoleDef {
        final String nameI18n;
        final String dataScope;
        final List<String> perms;
        RoleDef(String nameI18n, String dataScope, List<String> perms) {
            this.nameI18n = nameI18n; this.dataScope = dataScope; this.perms = perms;
        }
    }

    private static final class PermDef {
        final String code, name, ptype, parent, path;
        final int sort;
        PermDef(String code, String name, String ptype, String parent, String path, int sort) {
            this.code = code; this.name = name; this.ptype = ptype;
            this.parent = parent; this.path = path; this.sort = sort;
        }
    }
}
