package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.PermissionDtos.*;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.role.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.claw.server.common.security.RequirePermission;

/**
 * 后台权限矩阵（RBAC 重构）：菜单/按钮目录树 + 角色权限矩阵设置。
 *
 * <p>GET    /permissions/mine               当前登录用户的权限快照（前端 permStore 加载入口，仅需登录）
 * GET    /permissions/catalog              权限目录树
 * POST   /permissions/catalog              新增目录项
 * PUT    /permissions/catalog/{code}       修改目录项
 * DELETE /permissions/catalog/{code}       删除目录项
 * GET    /permissions/role/{roleId}        某角色的权限矩阵
 * PUT    /permissions/role/{roleId}        设置（覆盖）某角色的权限矩阵
 */
@RestController
@RequestMapping("/api/v1/admin/permissions")
@RequiredArgsConstructor
@Slf4j
public class AdminPermissionController {

    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final RoleRepository roleRepository;
    private final PermissionService permissionService;
    private final RoleGrantService roleGrantService;

    /** 菜单权限码前缀：menu:{navKey} —— navKey 与前端 web/src/nav.js 的菜单 key 一一对应。 */
    private static final String MENU_CODE_PREFIX = "menu:";
    /** 权限目录里「菜单」节点的 ptype（另一个取值是 BUTTON，不进导航树）。 */
    private static final String PTYPE_MENU = "MENU";

    /**
     * 当前登录用户的权限快照：userId + 生效角色 + 有效权限位集合 + 后端权威菜单树。
     * 仅需登录即可访问（不要求特定 admin 权限，否则无权限用户永远拿不到自己的权限集合）。
     * 前端 permStore 登录后调用此方法加载权限；menuStore 用其中的 menu 作为权威菜单源。
     *
     * <p>menu <b>按当前用户实际持有的权限位过滤</b>：只有用户拥有 {@code menu:{key}} 的节点才会下发，
     * 父分组在其下仍有可见子项时保留。持有通配符 {@code "*"} 的平台超管下发全量菜单。
     */
    @GetMapping("/mine")
    public ApiResult<MineResp> mine() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            throw BizException.of(40301, "error.permission.denied");
        }
        Set<String> permissions = permissionService.effectivePermissions(uid);
        List<String> roles = roleGrantService.listActive(uid).stream().map(RoleView::roleCode).toList();
        List<MenuNode> menu = myMenu(permissions);
        return ApiResult.ok(new MineResp(uid, roles, permissions, menu));
    }

    /**
     * 按用户权限位过滤权限目录中的 MENU 节点，并映射为前端导航树（key/label/path/icon/children）。
     *
     * <p>为何不直接返回 {@link #catalog()}：权限目录里既有 MENU 也有 BUTTON，且是全量目录；
     * 前端 menuStore 以 node.key 为索引（无 key 的节点会被 sanitizeNav 丢弃），
     * 因此这里既要做权限过滤，也要把 code 转译成 nav 结构的 key/label。
     */
    private List<MenuNode> myMenu(Set<String> permissions) {
        boolean allGranted = permissions.contains(PermissionService.WILDCARD);
        List<Permission> all = permissionRepository.findAll();
        Map<String, Permission> byCode = all.stream().collect(Collectors.toMap(Permission::getCode, p -> p));
        Map<String, List<Permission>> childrenMap = new HashMap<>();
        List<Permission> roots = new ArrayList<>();
        for (Permission p : all) {
            if (!PTYPE_MENU.equalsIgnoreCase(p.getPtype())) {
                continue;
            }
            if (p.getParentCode() == null || !byCode.containsKey(p.getParentCode())) {
                roots.add(p);
            } else {
                childrenMap.computeIfAbsent(p.getParentCode(), k -> new ArrayList<>()).add(p);
            }
        }
        List<MenuNode> nodes = new ArrayList<>();
        for (Permission root : roots) {
            MenuNode node = toMenuNode(root, permissions, allGranted, childrenMap, new HashSet<>());
            if (node != null) {
                nodes.add(node);
            }
        }
        nodes.sort(Comparator.comparingInt(n -> n.sortNo() == null ? 0 : n.sortNo()));
        return nodes;
    }

    /**
     * 递归构造导航节点：自身有权限 → 保留（并按权限裁剪子树）；自身无权限但子树有可见项 → 保留为分组。
     *
     * @param visiting 当前递归路径上的权限码，防止 parent_code 配成环时把请求打挂
     * @return 该节点对用户不可见时返回 null（连同其整棵子树一并剔除）
     */
    private MenuNode toMenuNode(Permission p, Set<String> permissions, boolean allGranted,
                                Map<String, List<Permission>> childrenMap, Set<String> visiting) {
        if (!visiting.add(p.getCode())) {
            // 目录数据成环（A 的 parent 是 B、B 的 parent 是 A）：就地截断，避免栈溢出。
            log.warn("权限目录存在环，已截断：code={}", p.getCode());
            return null;
        }
        List<MenuNode> kids = new ArrayList<>();
        for (Permission child : childrenMap.getOrDefault(p.getCode(), List.of())) {
            MenuNode kid = toMenuNode(child, permissions, allGranted, childrenMap, visiting);
            if (kid != null) {
                kids.add(kid);
            }
        }
        visiting.remove(p.getCode());
        kids.sort(Comparator.comparingInt(k -> k.sortNo() == null ? 0 : k.sortNo()));
        boolean visible = allGranted || permissions.contains(p.getCode());
        if (!visible && kids.isEmpty()) {
            return null;
        }
        return new MenuNode(navKeyOf(p.getCode()), p.getCode(), p.getName(), p.getPath(), p.getIcon(),
                p.getSortNo(), kids.isEmpty() ? null : kids);
    }

    /** 权限码 → 前端菜单 key：menu:production → production；非 menu: 前缀原样返回。 */
    private String navKeyOf(String permissionCode) {
        if (permissionCode != null && permissionCode.startsWith(MENU_CODE_PREFIX)) {
            return permissionCode.substring(MENU_CODE_PREFIX.length());
        }
        return permissionCode;
    }

    @GetMapping("/catalog")
    public ApiResult<List<PermissionNode>> catalog() {
        List<Permission> all = permissionRepository.findAll();
        Map<String, Permission> byCode = all.stream().collect(Collectors.toMap(Permission::getCode, p -> p));
        List<PermissionNode> roots = new ArrayList<>();
        Map<String, List<PermissionNode>> childrenMap = new HashMap<>();
        for (Permission p : all) {
            PermissionNode node = toNode(p, null);
            if (p.getParentCode() == null || !byCode.containsKey(p.getParentCode())) {
                roots.add(node);
            } else {
                childrenMap.computeIfAbsent(p.getParentCode(), k -> new ArrayList<>()).add(node);
            }
        }
        roots.sort(Comparator.comparingInt(n -> n.sortNo() == null ? 0 : n.sortNo()));
        fill(childrenMap, roots);
        return ApiResult.ok(roots);
    }

    private PermissionNode toNode(Permission p, List<PermissionNode> children) {
        return new PermissionNode(p.getId(), p.getCode(), p.getName(), p.getPtype(), p.getParentCode(),
                p.getPath(), p.getSortNo(), p.getIcon(), children);
    }

    private void fill(Map<String, List<PermissionNode>> childrenMap, List<PermissionNode> nodes) {
        for (PermissionNode n : nodes) {
            List<PermissionNode> kids = childrenMap.get(n.code());
            if (kids != null) {
                kids.sort(Comparator.comparingInt(k -> k.sortNo() == null ? 0 : k.sortNo()));
                fill(childrenMap, kids);
                nodes.set(nodes.indexOf(n), new PermissionNode(n.id(), n.code(), n.name(), n.ptype(),
                        n.parentCode(), n.path(), n.sortNo(), n.icon(), kids));
            }
        }
    }

    @PostMapping("/catalog")
    @RequirePermission("permission:create")
    public ApiResult<PermissionNode> createCatalog(@RequestBody UpsertPermission req) {
        if (permissionRepository.findByCode(req.code()).isPresent()) {
            throw new BizException(40901, "permission.code.exists");
        }
        Permission p = Permission.builder()
                .code(req.code()).name(req.name())
                .ptype(req.ptype() == null ? "MENU" : req.ptype())
                .parentCode(req.parentCode()).path(req.path())
                .sortNo(req.sortNo() == null ? 0 : req.sortNo()).icon(req.icon())
                .build();
        p = permissionRepository.save(p);
        return ApiResult.ok(toNode(p, null));
    }

    @PutMapping("/catalog/{code}")
    @RequirePermission("permission:update")
    public ApiResult<PermissionNode> updateCatalog(@PathVariable String code, @RequestBody UpsertPermission req) {
        Permission p = permissionRepository.findByCode(code)
                .orElseThrow(() -> new BizException(40401, "permission.not.found"));
        if (req.name() != null) p.setName(req.name());
        if (req.ptype() != null) p.setPtype(req.ptype());
        if (req.parentCode() != null) p.setParentCode(req.parentCode());
        if (req.path() != null) p.setPath(req.path());
        if (req.sortNo() != null) p.setSortNo(req.sortNo());
        if (req.icon() != null) p.setIcon(req.icon());
        p = permissionRepository.save(p);
        return ApiResult.ok(toNode(p, null));
    }

    @DeleteMapping("/catalog/{code}")
    @RequirePermission("permission:delete")
    public ApiResult<Void> deleteCatalog(@PathVariable String code) {
        Permission p = permissionRepository.findByCode(code)
                .orElseThrow(() -> new BizException(40401, "permission.not.found"));
        rolePermissionRepository.deleteByPermissionCode(code);
        permissionRepository.delete(p);
        return ApiResult.ok();
    }

    @GetMapping("/role/{roleId}")
    public ApiResult<List<RolePermissionRow>> rolePermissions(@PathVariable Long roleId) {
        if (!roleRepository.existsById(roleId)) throw new BizException(40401, "role.not.found");
        Map<String, Permission> permByCode = permissionRepository.findAll().stream()
                .collect(Collectors.toMap(Permission::getCode, p -> p));
        return ApiResult.ok(rolePermissionRepository.findByRoleId(roleId).stream()
                .map(rp -> new RolePermissionRow(rp.getPermissionCode(),
                        permByCode.getOrDefault(rp.getPermissionCode(), null) == null ? rp.getPermissionCode()
                                : permByCode.get(rp.getPermissionCode()).getName(),
                        rp.getCanRead(), rp.getCanCreate(), rp.getCanUpdate(), rp.getCanDelete(),
                        rp.getCanExport(), rp.getButtonsJson()))
                .sorted(Comparator.comparing(RolePermissionRow::permissionCode))
                .toList());
    }

    @Transactional
    @PutMapping("/role/{roleId}")
    @RequirePermission("permission:update")
    public ApiResult<Void> setRolePermissions(@PathVariable Long roleId, @RequestBody SetRolePermissionReq req) {
        if (!roleRepository.existsById(roleId)) throw new BizException(40401, "role.not.found");
        // 幂等 upsert：先取出现有矩阵，按 permissionCode 更新或新建，避免 (role_id,permission_code) 唯一约束冲突。
        Map<String, RolePermission> existing = rolePermissionRepository.findByRoleId(roleId).stream()
                .collect(Collectors.toMap(RolePermission::getPermissionCode, rp -> rp));
        Set<String> incoming = new HashSet<>();
        for (RolePermissionItem it : req.items()) {
            if (!permissionRepository.findByCode(it.permissionCode()).isPresent()) continue;
            incoming.add(it.permissionCode());
            RolePermission rp = existing.get(it.permissionCode());
            if (rp == null) {
                rp = RolePermission.builder().roleId(roleId).permissionCode(it.permissionCode())
                        .createdAt(Instant.now()).build();
            }
            rp.setCanRead(Boolean.TRUE.equals(it.canRead()));
            rp.setCanCreate(Boolean.TRUE.equals(it.canCreate()));
            rp.setCanUpdate(Boolean.TRUE.equals(it.canUpdate()));
            rp.setCanDelete(Boolean.TRUE.equals(it.canDelete()));
            rp.setCanExport(Boolean.TRUE.equals(it.canExport()));
            rp.setButtonsJson(it.buttonsJson() == null ? "{}" : it.buttonsJson());
            rp.setUpdatedAt(Instant.now());
            rolePermissionRepository.save(rp);
        }
        // 回收前端未包含的权限点（支持取消某菜单/按钮的授权）
        for (RolePermission stale : existing.values()) {
            if (!incoming.contains(stale.getPermissionCode())) {
                rolePermissionRepository.delete(stale);
            }
        }
        return ApiResult.ok();
    }
}
