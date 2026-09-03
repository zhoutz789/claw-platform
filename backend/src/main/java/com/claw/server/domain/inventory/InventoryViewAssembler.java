package com.claw.server.domain.inventory;

import com.claw.server.common.dto.InventoryViews;

/**
 * 库存视图装配器（模块三 · M3-1/2/3/4）。
 *
 * <p>位于 <b>domain 层</b>，负责把 {@code domain} 实体 / 作用域值对象映射为
 * {@code common} 层的纯 DTO，从而使 {@code InventoryViews} 不反向依赖 domain，
 * 满足既有架构守护（{@code common} 层不得依赖 {@code domain} 层）。
 *
 * <p>转换规则：
 * <ul>
 *   <li>{@code Inventory → InventoryRowView}：截断敏感货值字段；</li>
 *   <li>{@code InventoryScope.ScopeInfo → ScopeInfoView}：作用域等级以枚举名字符串透传，
 *       使 common 层只需持有纯 {@code ScopeInfoView}（不引用 {@code InventoryScope}）。</li>
 * </ul>
 */
public final class InventoryViewAssembler {

    private InventoryViewAssembler() {
    }

    /** 库存实体 → 安全只读行视图（截断敏感货值字段）。null 安全。 */
    public static InventoryViews.InventoryRowView toRowView(Inventory inv) {
        if (inv == null) {
            return null;
        }
        return new InventoryViews.InventoryRowView(
                inv.getId(),
                inv.getAssetId(),
                inv.getDeviceId(),
                inv.getSerialNumber(),
                inv.getProductId(),
                inv.getOwnershipType(),
                inv.getCurrentStatus(),
                inv.getOwnerManufacturerId(),
                inv.getHolderStationId(),
                inv.getCustodyId(),
                inv.getInboundAt(),
                inv.getUpdatedAt());
    }

    /** 库存作用域值对象 → common 层视图（scopeLevel 以枚举名字符串透传）。null 安全。 */
    public static InventoryViews.ScopeInfoView toScopeView(InventoryScope.ScopeInfo scope) {
        if (scope == null) {
            return null;
        }
        return new InventoryViews.ScopeInfoView(
                scope.principalType(),
                scope.principalId(),
                scope.viaSubAccount(),
                scope.platformAdmin(),
                scope.scopeLevel().name(),
                scope.subordinateStationIds(),
                scope.effectiveManufacturerId(),
                scope.effectiveStationId());
    }
}
