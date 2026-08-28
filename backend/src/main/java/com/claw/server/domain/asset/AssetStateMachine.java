package com.claw.server.domain.asset;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.AssetStatus;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 资产状态机（对应 assets.status + asset_status_logs 审计）。
 *
 * <p>合法流转（含资产闭环终点）：
 * <pre>
 *   在库(IN_STOCK) → 使用中(IN_USE) ⇄ 共享中(SHARED)
 *                              │
 *            ┌─────────────────┼─────────────────────┐
 *            ↓                 ↓                     ↓
 *        维修(REPAIR)      停用(DISABLED)        退役(RETIRED)
 *            │                 │                     │
 *            └─────→ 报废(SCRAPPED, 终态) ←──────────┘
 *                                  │
 *                            回收(RECYCLED, 终态)  ← 残值/梯次利用
 * </pre>
 * 退役(RETIRED) 是闭环关键节点：资产结束服务、待残值评估；回收(RECYCLED) 触发残值返还，
 * 二者由生命周期扫描器 {@code AssetLifecycleScheduler} 按 SOH/年限自动推进，亦可手工流转。
 * 非法迁移抛 {@code ILLEGAL_ASSET_STATUS}（技术文档 2.1 设计原则 3：状态机字段 + 审计）。
 * 无 Spring 依赖，可独立单元测试。
 */
public class AssetStateMachine {

    private static final Map<AssetStatus, Set<AssetStatus>> TRANSITIONS = Map.of(
            AssetStatus.IN_STOCK, EnumSet.of(AssetStatus.IN_USE, AssetStatus.REPAIR,
                    AssetStatus.DISABLED, AssetStatus.RETIRED, AssetStatus.SCRAPPED, AssetStatus.LISTED),
            AssetStatus.IN_USE, EnumSet.of(AssetStatus.SHARED, AssetStatus.REPAIR,
                    AssetStatus.DISABLED, AssetStatus.RETIRED, AssetStatus.SCRAPPED, AssetStatus.LISTED),
            AssetStatus.SHARED, EnumSet.of(AssetStatus.IN_USE, AssetStatus.REPAIR,
                    AssetStatus.DISABLED, AssetStatus.RETIRED, AssetStatus.SCRAPPED, AssetStatus.LISTED),
            // LISTED：资产大厅·公开可见（V36 转让）。可由运营态进入，也可退回运营态/走回收终点。
            AssetStatus.LISTED, EnumSet.of(AssetStatus.IN_USE, AssetStatus.SHARED, AssetStatus.IN_STOCK,
                    AssetStatus.REPAIR, AssetStatus.DISABLED, AssetStatus.RETIRED, AssetStatus.SCRAPPED),
            AssetStatus.REPAIR, EnumSet.of(AssetStatus.IN_STOCK, AssetStatus.IN_USE,
                    AssetStatus.RETIRED, AssetStatus.SCRAPPED),
            AssetStatus.DISABLED, EnumSet.of(AssetStatus.IN_STOCK, AssetStatus.RETIRED, AssetStatus.SCRAPPED),
            AssetStatus.RETIRED, EnumSet.of(AssetStatus.RECYCLED, AssetStatus.SCRAPPED),
            AssetStatus.RECYCLED, EnumSet.noneOf(AssetStatus.class),
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
