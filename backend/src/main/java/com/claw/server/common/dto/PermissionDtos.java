package com.claw.server.common.dto;

import java.util.List;

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
}
