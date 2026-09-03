package com.claw.server.common.dto;

import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.common.enums.PrincipalType;

import java.time.Instant;
import java.util.List;

/**
 * 库存视图出参 DTO（模块三 · M3-1/2/3/4）。
 *
 * <p>全部只读投影，<b>一律不含</b>授信域敏感字段
 * {@code unitValue / valueCurrency / unitValueSource}（增量 C 字段，A.4 硬约束）。
 *
 * <p><b>架构约束</b>：本类位于 {@code common} 层，被所有域复用，<b>不得反向依赖</b>
 * {@code domain} 层（既有守护 {@code ArchitectureBoundaryTest.commonLayerMustNotDependOnDomains}）。
 * 因此：
 * <ul>
 *   <li>行投影字段仅用基础类型与 {@code common.enums} 中的枚举，<b>不含实体 {@code Inventory}</b>；</li>
 *   <li>实体 → DTO 映射（{@code Inventory.getXxx()}）与作用域值对象转换统一上移到
 *       {@code domain.inventory.InventoryViewAssembler}，common 层只保留纯数据结构；</li>
 *   <li>作用域等级以 {@code String} 透传（枚举名），作用域值对象以本层 {@link ScopeInfoView} 复刻，
 *       均不引用 {@code domain.inventory.InventoryScope}。</li>
 * </ul>
 *
 * 字段名与 {@code Inventory} 实体保持一致，便于前端列定义零改造。
 */
public final class InventoryViews {

    private InventoryViews() {
    }

    /** 库存单行安全只读投影（不含货值三字段）。实体映射见 InventoryViewAssembler。 */
    public record InventoryRowView(
            Long id,
            Long assetId,
            Long deviceId,
            String serialNumber,
            Long productId,
            OwnershipType ownershipType,
            LifecycleStatus currentStatus,
            Long ownerManufacturerId,
            Long holderStationId,
            Long custodyId,
            Instant inboundAt,
            Instant updatedAt
    ) {
    }

    /** 状态计数（byStatus 维度）。 */
    public record StatusCount(String status, long count) {
    }

    /** 站点计数（byStation 维度）。 */
    public record StationCount(Long stationId, long count) {
    }

    /** 厂家计数（byManufacturer 维度）。 */
    public record ManufacturerCount(Long manufacturerId, long count) {
    }

    /** 所有权类型计数（byOwnership 维度）。 */
    public record OwnershipCount(String ownershipType, long count) {
    }

    /** 服务站分组（按 holderStationId 分组后的子视图）。 */
    public record StationGroupView(
            Long stationId,
            long total,
            List<StatusCount> byStatus,
            List<InventoryRowView> rows,
            boolean truncated
    ) {
    }

    /**
     * 作用域值对象（common 层复刻，不引用 domain.inventory.InventoryScope）。
     *
     * <p>字段与 {@code InventoryScope.ScopeInfo} 一一对齐；{@code scopeLevel} 以枚举名字符串透传，
     * 由 {@code InventoryViewAssembler.toScopeView} 在 domain 层完成转换。
     */
    public record ScopeInfoView(
            PrincipalType principalType,
            Long principalId,
            boolean viaSubAccount,
            boolean platformAdmin,
            String scopeLevel,
            List<Long> subordinateStationIds,
            Long effectiveManufacturerId,
            Long effectiveStationId
    ) {
    }

    /** 角色作用域双视图（GET /me 出参）。 */
    public record InventoryScopeView(
            ScopeInfoView scope,
            List<InventoryRowView> current,
            long currentTotal,
            boolean currentTruncated,
            List<StationGroupView> subordinateStations,
            long subordinateTotal,
            InventoryStatsView stats
    ) {
        /** 返回带统计段的新实例（不可变 copy）。 */
        public InventoryScopeView withStats(InventoryStatsView stats) {
            return new InventoryScopeView(scope, current, currentTotal, currentTruncated,
                    subordinateStations, subordinateTotal, stats);
        }
    }

    /** 统计报表（GET /stats 出参）。scopeLevel 以枚举名字符串透传。 */
    public record InventoryStatsView(
            String scopeLevel,
            long total,
            long ownedByMfgTotal,
            long consignedTotal,
            long fullTotal,
            double consignedRatio,
            long atFactoryCount,
            long stationCount,
            long manufacturerCount,
            List<StatusCount> byStatus,
            List<StationCount> byStation,
            List<ManufacturerCount> byManufacturer,
            List<OwnershipCount> byOwnership,
            Instant generatedAt
    ) {
    }

    /** 模块三取数筛选条件（GET /me 入参，由 Controller 完成枚举校验后传入）。 */
    public record InventoryFilter(
            String status,
            String ownershipType,
            Long stationId,
            boolean withRows,
            int limit
    ) {
    }
}
