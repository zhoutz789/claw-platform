package com.claw.server.domain.subaccount;

import java.util.List;
import java.util.Set;

/**
 * 授权结果（增量 C · O30）。
 *
 * @param grantId          授权 ID
 * @param grantMode        ALL / PARTIAL
 * @param effectivePerms   实际生效的权限码集合
 * @param removedPerms     被「∩ 主账号模板」剔除的越权项（PARTIAL 模式下）
 * @param templateCode     ALL 模式跟随的角色模板码
 */
public record GrantResult(
        Long grantId,
        String grantMode,
        Set<String> effectivePerms,
        List<String> removedPerms,
        String templateCode) {

    /** 是否存在被自动剔除的越权权限（前端据此提示「已自动剔除 N 项越权权限」）。 */
    public boolean hasRemoved() {
        return removedPerms != null && !removedPerms.isEmpty();
    }

    /** 被剔除的越权项数量。 */
    public int removedCount() {
        return removedPerms == null ? 0 : removedPerms.size();
    }
}
