package com.claw.server.domain.onboarding;

import java.math.BigDecimal;

/**
 * 激活链路结果（增量 C · §6.3）。
 *
 * @param principalId   新建/复用的主体 ID
 * @param principalType STATION / MANUFACTURER / MERCHANT
 * @param bindingId     principal_bindings.id（已存在时为既有 id）
 * @param templateCode  授予的角色模板码
 * @param creditLimit   回填的授信额度（寄售设备名义货值上限）
 * @param grantedPerms  授予的权限码数量
 * @param retried       本次是否为「重试激活」（主体已存在则复用）
 */
public record ActivationResult(
        Long principalId,
        String principalType,
        Long bindingId,
        String templateCode,
        BigDecimal creditLimit,
        int grantedPerms,
        boolean retried) {
}
