package com.claw.server.common.dto;

import java.util.List;
import java.util.Set;

/** 权限目录 / 角色权限矩阵 出入参。 */
public final class PermissionDtos {

    private PermissionDtos() {
    }

    /** 菜单树节点。 */
    public record PermissionNode(Long id, String code, String name, String ptype, String parentCode,
                                 String path, Integer sortNo, String icon, List<PermissionNode> children) {
    }

    /** 角色权限矩阵单行。 */
    public record RolePermissionRow(String permissionCode, String name, Boolean canRead, Boolean canCreate,
                                    Boolean canUpdate, Boolean canDelete, Boolean canExport, String buttonsJson) {
    }

    /** 设置角色权限：一次提交整张矩阵。 */
    public record RolePermissionItem(String permissionCode, Boolean canRead, Boolean canCreate, Boolean canUpdate,
                                     Boolean canDelete, Boolean canExport, String buttonsJson) {
    }

    public record SetRolePermissionReq(List<RolePermissionItem> items) {
    }

    /** 目录项增改。 */
    public record UpsertPermission(String code, String name, String ptype, String parentCode, String path,
                                   Integer sortNo, String icon) {
    }

    /**
     * 当前登录用户权限快照（权限通电内核）：供前端 permStore 一次性加载。
     * <ul>
     *   <li>userId：当前用户 ID；</li>
     *   <li>roles：生效角色包 code 列表；</li>
     *   <li>permissions：有效权限位集合（含 RBAC + 角色包 grants，可能含通配符 "*"）；</li>
     *   <li>menu：后端权威菜单树（权限目录，含 menu/button 节点），供前端 menuStore 接入。</li>
     * </ul>
     */
    public record MineResp(Long userId, List<String> roles, Set<String> permissions, List<PermissionNode> menu) {
    }
}
