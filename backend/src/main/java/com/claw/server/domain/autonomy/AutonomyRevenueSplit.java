package com.claw.server.domain.autonomy;

import java.math.BigDecimal;

/**
 * 无人车自主任务三方分账结果（AU7）。
 * <p>车主(asset-owner) / 平台(算法, platform=algorithm) / 服务方(provider = 站点 + 保险)。
 * 由 {@link AutonomyRevenueService#computeAutonomyRevenueSplit(Long, BigDecimal)} 返回。
 */
public record AutonomyRevenueSplit(
        Long assetId,
        BigDecimal gross,
        BigDecimal ownerShare,
        BigDecimal platformAlgorithmShare,
        BigDecimal providerShare,
        String basis) {
}
