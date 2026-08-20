package com.claw.server.domain.asset;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.AssetStatus;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 资产状态机（对应 assets.status + asset_status_logs 审计）。
 *
 * <p>合法流转：在库 → 使用中 → 共享中 → 维修/停用/报废。报废为终态。
 * 非法迁移抛 {@code ILLEGAL_ASSET_STATUS}（技术文档 2.1 设计原则 3：状态机字段 + 审计）。
 * 无 Spring 依赖，可独立单元测试。
 */
public class AssetStateMachine {

    private static final Map<AssetStatus, Set<AssetStatus>> TRANSITIONS = Map.of(
            AssetStatus.IN_STOCK, EnumSet.of(AssetStatus.IN_USE, AssetStatus.REPAIR,
                    AssetStatus.DISABLED, AssetStatus.SCRAPPED),
            AssetStatus.IN_USE, EnumSet.of(AssetStatus.SHARED, AssetStatus.REPAIR,
                    AssetStatus.DISABLED, AssetStatus.SCRAPPED),
            AssetStatus.SHARED, EnumSet.of(AssetStatus.IN_USE, AssetStatus.REPAIR,
                    AssetStatus.DISABLED, AssetStatus.SCRAPPED),
            AssetStatus.REPAIR, EnumSet.of(AssetStatus.IN_STOCK, AssetStatus.IN_USE, AssetStatus.SCRAPPED),
            AssetStatus.DISABLED, EnumSet.of(AssetStatus.IN_STOCK, AssetStatus.SCRAPPED),
            AssetStatus.SCRAPPED, EnumSet.noneOf(AssetStatus.class)
    );

    /** 校验流转合法性；非法抛业务异常。 */
    public static void assertTransition(AssetStatus from, AssetStatus to) {
        if (from == to) {
            return;
        }
        Set<AssetStatus> allowed = TRANSITIONS.getOrDefault(from, EnumSet.noneOf(AssetStatus.class));
        if (!allowed.contains(to)) {
            throw BizException.of(BizException.ILLEGAL_ASSET_STATUS, "error.asset.status.illegal",
                    from, to);
        }
    }

    public static boolean canTransition(AssetStatus from, AssetStatus to) {
        if (from == to) {
            return true;
        }
        return TRANSITIONS.getOrDefault(from, EnumSet.noneOf(AssetStatus.class)).contains(to);
    }
}
