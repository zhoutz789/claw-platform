package com.claw.server.domain.inventory;

import com.claw.server.common.enums.PrincipalType;

import java.util.List;

/**
 * 库存作用域模型（模块三 · M3-1/2/3）。
 *
 * <p>把「当前登录账号在库存域里能看到哪些行、按什么粒度分组」抽象成两个值对象：
 * <ul>
 *   <li>{@link ScopeLevel} —— 作用域等级（全平台 / 厂家 / 服务站 / 商家 / 未绑定）；</li>
 *   <li>{@link ScopeInfo} —— 解析出来的具体作用域（主体类型、ID、是否平台管理员、
 *       下属服务站集合、生效的厂家/站点 ID）。</li>
 * </ul>
 *
 * 由 {@link InventoryScopeService} 产出；服务层（{@code InventoryService}）只消费、
 * 不隐式读 {@code AuthContext}，便于独立测试与模块四复用。
 */
public final class InventoryScope {

    private InventoryScope() {
    }

    /** 库存作用域等级。 */
    public enum ScopeLevel {
        /** 平台管理员：可见全平台。 */
        PLATFORM,
        /** 厂家：可见自有 + 寄售在站。 */
        MANUFACTURER,
        /** 服务站：仅可见本站在库寄售。 */
        STATION,
        /** 商家：库存域无数据。 */
        MERCHANT,
        /** 未绑定任何业务主体（含 dev-open-access 下解析不出）。不抛 403，返回空 + 引导。 */
        NONE
    }

    /**
     * 库存作用域值对象（不可变）。
     *
     * @param principalType 解析到的主体类型（null 表示未绑定）
     * @param principalId 解析到的主体 ID（null 表示未绑定）
     * @param viaSubAccount 是否经子账号回溯而来
     * @param platformAdmin 是否平台管理员（靠 "*" 通配命中）
     * @param scopeLevel 作用域等级
     * @param subordinateStationIds 下属服务站 ID 集合（厂家=寄售在站集合；站点/管理员由取数逻辑决定）
     * @param effectiveManufacturerId 生效厂家 ID（越权覆盖后的最终厂家；无则 null）
     * @param effectiveStationId 生效服务站 ID（越权覆盖后的最终站点；无则 null）
     */
    public record ScopeInfo(
            PrincipalType principalType,
            Long principalId,
            boolean viaSubAccount,
            boolean platformAdmin,
            ScopeLevel scopeLevel,
            List<Long> subordinateStationIds,
            Long effectiveManufacturerId,
            Long effectiveStationId
    ) {
    }
}
