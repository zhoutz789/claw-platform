package com.claw.server.common.security;

/**
 * 数据范围字段映射（权限通电 P1-T03）：把 {@link DataScopeResult} 的各维度翻译到具体实体的列名。
 *
 * <p>调用方（各域 list 查询，位于 domain/web 层）按自身实体结构提供列名；某维度为 null 时
 * {@link DataScopeSpec} 对该维度不生成谓词（或退化为基于 owner 子查询，见下）。
 *
 * <ul>
 *   <li>{@code ownerId}    —— SELF 的归属人列（如 asset.ownerId / user.id / order.buyerUserId）；</li>
 *   <li>{@code departmentId}—— DEPARTMENT / CUSTOM 的部门列（实体直带时直查，否则走 owner 子查询）；</li>
 *   <li>{@code orgCode}    —— DEPARTMENT_AND_BELOW 的层级码列（实体直带时 LIKE，否则走 department 子查询）；</li>
 *   <li>{@code type}       —— TYPE 的类型列（如 asset.assetType / order.assetType）；</li>
 *   <li>{@code ownerEntity}     —— owner 子查询目标实体类（如 User），由 domain 调用方传入，common 层不反向依赖 domain；</li>
 *   <li>{@code departmentEntity}—— 部门子查询目标实体类（如 Department），同上。</li>
 * </ul>
 */
public record DataScopeFieldMapping(String ownerId, String departmentId, String orgCode, String type,
                                    Class<?> ownerEntity, Class<?> departmentEntity) {

    /** 便捷构造（无子查询目标类；仅支持实体直带列的维度）。 */
    public static DataScopeFieldMapping of(String ownerId, String departmentId, String orgCode, String type) {
        return new DataScopeFieldMapping(ownerId, departmentId, orgCode, type, null, null);
    }

    /** 完整构造（提供子查询目标类以支持 owner/department 子查询维度）。 */
    public static DataScopeFieldMapping of(String ownerId, String departmentId, String orgCode, String type,
                                           Class<?> ownerEntity, Class<?> departmentEntity) {
        return new DataScopeFieldMapping(ownerId, departmentId, orgCode, type, ownerEntity, departmentEntity);
    }
}
