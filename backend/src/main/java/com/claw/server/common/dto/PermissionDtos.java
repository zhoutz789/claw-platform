package com.claw.server.common.dto;

import java.util.List;
import java.util.Set;

/** 权限目录 / 角色权限矩阵 出入参。 */
public final class PermissionDtos {

    private PermissionDtos() {
    }

    /** 权限目录树节点（code = 权限码），供权限矩阵 / 菜单管理页消费。 */
    public record PermissionNode(Long id, String code, String name, String ptype, String parentCode,
                                 String path, Integer sortNo, String icon, List<PermissionNode> children) {
    }

    /**
     * 导航树节点（前端侧边栏视角：key = nav.js 的菜单 key，label = 菜单显示名）。
     *
     * <p>与 {@link PermissionNode} 的分工：权限目录给「菜单管理 / 权限矩阵」用，以权限码为主键语义；
     * 本结构给「我的菜单」用，字段与前端 menuStore.sanitizeNav 的契约（key/label/path/icon/children）对齐
     * —— 前端按 node.key 建索引并丢弃无 key 的节点，若这里仍返回 {@code code} 字段，
     * 整棵菜单树会在清洗阶段被丢空，表现为「真实后端下菜单全不显示」。
     */
    public record MenuNode(String key, String code, String label, String path, String icon,
                           Integer sortNo, List<MenuNode> children) {
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
     *   <li>menu：后端权威菜单树（<b>已按当前用户 permissions 过滤</b>的 MENU 节点），供前端 menuStore 接入。</li>
     * </ul>
     */
    public record MineResp(Long userId, List<String> roles, Set<String> permissions, List<MenuNode> menu) {
    }
}
