package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.PermissionDtos.*;
import com.claw.server.domain.role.*;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 后台权限矩阵（RBAC 重构）：菜单/按钮目录树 + 角色权限矩阵设置。
 *
 * <p>GET    /permissions/catalog              权限目录树
 * POST   /permissions/catalog              新增目录项
 * PUT    /permissions/catalog/{code}       修改目录项
 * DELETE /permissions/catalog/{code}       删除目录项
 * GET    /permissions/role/{roleId}        某角色的权限矩阵
 * PUT    /permissions/role/{roleId}        设置（覆盖）某角色的权限矩阵
 */
@RestController
@RequestMapping("/api/v1/admin/permissions")
@RequiredArgsConstructor
public class AdminPermissionController {

    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final RoleRepository roleRepository;

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
