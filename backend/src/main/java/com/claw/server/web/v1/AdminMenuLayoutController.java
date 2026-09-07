package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.PermissionDtos.MenuLayoutItem;
import com.claw.server.common.dto.PermissionDtos.MenuLayoutSaveReq;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.role.Permission;
import com.claw.server.domain.role.PermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 菜单布局后端持久化（V80）。
 *
 * <p>背景：菜单布局此前分层落在浏览器 localStorage（不稳定、且只属于单机），
 * 现统一收敛到后端 —— {@code claw.permissions} 已是菜单结构真源（parent_code / sort_no /
 * path / icon / name），并由 V80 补上两个布局标志位：
 * <ul>
 *   <li>{@code hidden}：菜单管理 / 侧边栏中隐藏该节点（连同整棵子树）；</li>
 *   <li>{@code custom}：用户自建菜单项 —— 只有自建项才会在批量保存时被回收。</li>
 * </ul>
 *
 * <p>GET    /api/v1/admin/menu-layout  拉取全部 MENU 行（扁平列表，按 sort_no 排序）
 * PUT    /api/v1/admin/menu-layout  原子覆盖式保存整张菜单（新建 / 更新 / 回收自建项）
 */
@RestController
@RequestMapping("/api/v1/admin/menu-layout")
@RequiredArgsConstructor
@Slf4j
public class AdminMenuLayoutController {

    /** 权限目录里「菜单」节点的 ptype（另一个取值是 BUTTON，不进导航树）。 */
    private static final String PTYPE_MENU = "MENU";

    private final PermissionRepository permissionRepository;

    /**
     * 拉取菜单布局：全部 ptype = 'MENU' 的目录行（大小写不敏感），按 sort_no 升序。
     *
     * <p>返回扁平列表而非树 —— 菜单管理页自己按 parentCode 组装，后端不做结构假设，
     * 保存时前端也按同样的扁平结构回传，天然幂等。
     */
    @GetMapping
    @RequirePermission("menu:update")
    public ApiResult<List<MenuLayoutItem>> list() {
        List<MenuLayoutItem> items = new ArrayList<>();
        for (Permission p : permissionRepository.findAll()) {
            if (!PTYPE_MENU.equalsIgnoreCase(p.getPtype())) {
                continue;
            }
            items.add(toItem(p));
        }
        items.sort(Comparator
                .comparingInt((MenuLayoutItem i) -> i.sortNo() == null ? 0 : i.sortNo())
                .thenComparing(i -> i.code() == null ? "" : i.code()));
        return ApiResult.ok(items);
    }

    /**
     * 原子保存整张菜单布局。
     *
     * <p>语义为「覆盖」：payload 即菜单全貌。
     * <ol>
     *   <li>按 code upsert（存在则更新布局字段，不存在则新建 ptype='MENU' 的行）；</li>
     *   <li>回收「用户自建且本次未提交」的行 —— <b>绝不删除 custom = FALSE 的内置种子菜单</b>，
     *       否则一次误提交会把 Flyway 播下的菜单全表清空。</li>
     * </ol>
     */
    @Transactional
    @PutMapping
    @RequirePermission("menu:update")
    public ApiResult<Void> save(@RequestBody(required = false) MenuLayoutSaveReq req) {
        List<MenuLayoutItem> items = (req == null || req.items() == null) ? List.of() : req.items();

        // a) 本次提交的权限码集合（跳过空码，避免空串建成脏行 / 误删）
        Set<String> incoming = new HashSet<>();
        for (MenuLayoutItem item : items) {
            if (item == null || isBlank(item.code())) {
                continue;
            }
            incoming.add(item.code().trim());
        }

        // b) upsert：存在的更新布局字段，不存在的新建
        for (MenuLayoutItem item : items) {
            if (item == null || isBlank(item.code())) {
                continue;
            }
            String code = item.code().trim();
            Permission p = permissionRepository.findByCode(code).orElse(null);
            if (p == null) {
                permissionRepository.save(newPermission(code, item));
            } else {
                applyLayout(p, item);
                permissionRepository.save(p);
            }
        }

        // c) 回收：仅删除「用户自建」且本次未提交的菜单行；内置种子（custom=false）永不删除
        List<Permission> stale = new ArrayList<>();
        for (Permission p : permissionRepository.findAll()) {
            if (!Boolean.TRUE.equals(p.getCustom())) {
                continue;
            }
            if (p.getCode() != null && incoming.contains(p.getCode())) {
                continue;
            }
            stale.add(p);
        }
        if (!stale.isEmpty()) {
            log.info("菜单布局保存：回收用户自建菜单项 {} 个", stale.size());
            permissionRepository.deleteAll(stale);
        }
        return ApiResult.ok();
    }

    /** Permission → 布局 DTO（标志位统一归一化，null 视为 false，避免前端收到 null）。 */
    private static MenuLayoutItem toItem(Permission p) {
        return new MenuLayoutItem(p.getCode(), p.getParentCode(), p.getName(), p.getPath(), p.getIcon(),
                p.getSortNo() == null ? 0 : p.getSortNo(),
                Boolean.TRUE.equals(p.getHidden()), Boolean.TRUE.equals(p.getCustom()));
    }

    /** 已存在行：只覆盖布局相关字段（name 为空则保留原名，ptype / createdAt 不动）。 */
    private static void applyLayout(Permission p, MenuLayoutItem item) {
        if (!isBlank(item.name())) {
            p.setName(item.name().trim());
        }
        p.setParentCode(normalizeParent(item.parentCode()));
        p.setPath(item.path());
        p.setIcon(item.icon());
        p.setSortNo(item.sortNo() == null ? 0 : item.sortNo());
        p.setHidden(Boolean.TRUE.equals(item.hidden()));
        p.setCustom(Boolean.TRUE.equals(item.custom()));
    }

    /** 新建行：payload 中未出现的行按「用户自建」处理（自定义菜单项），内置种子由 Flyway 维护。 */
    private static Permission newPermission(String code, MenuLayoutItem item) {
        return Permission.builder()
                .code(code)
                .name(isBlank(item.name()) ? code : item.name().trim())
                .ptype(PTYPE_MENU)
                .parentCode(normalizeParent(item.parentCode()))
                .path(item.path())
                .icon(item.icon())
                .sortNo(item.sortNo() == null ? 0 : item.sortNo())
                .hidden(Boolean.TRUE.equals(item.hidden()))
                .custom(Boolean.TRUE.equals(item.custom()))
                .createdAt(Instant.now())
                .build();
    }

    /** 父码归一化：空串视为「根节点」（NULL），避免前端回传 "" 造成父码查不到而降级成根。 */
    private static String normalizeParent(String parentCode) {
        if (isBlank(parentCode)) {
            return null;
        }
        return parentCode.trim();
    }

    /** 空白判定（null / 全空白均为空）。 */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
