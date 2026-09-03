package com.claw.server.domain.station;

import com.claw.server.common.dto.StationViews;
import com.claw.server.domain.inventory.InventoryScope;
import com.claw.server.domain.inventory.InventoryScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 服务站模块作用域解析（模块四 · 薄封装）。
 *
 * <p>完全复用模块三 {@link InventoryScopeService#resolveCurrent}，把"当前登录账号在服务站域里
 * 能看到哪些站"收敛成统一的 {@link #allowedStationIds}：站点=自身站、厂家=下属寄售站集合、平台管理员=全平台。
 * 越权覆盖（如服务站传他人 stationId）由 {@code InventoryScopeService.assertOverrideAllowed} 抛 40301。
 *
 * <p>本服务只读，不触碰任何写链路，可安全注入到三层服务/控制器（BC-5）。
 */
@Service
@RequiredArgsConstructor
public class StationScopeService {

    private final InventoryScopeService inventoryScopeService;

    /**
     * 解析当前账号在服务站域允许看到的站点 ID 集合。
     *
     * @param overrideStationId 增强筛选站点 ID（null 表示不覆盖）
     * @return 允许站集合；{@code null} 表示"不限制"（平台管理员看全平台）；
     *         空集合表示"无数据"（未绑定主体等）
     * @throws com.claw.server.common.api.BizException 40301 越权覆盖被拒
     */
    public List<Long> allowedStationIds(Long overrideStationId) {
        return toAllowed(inventoryScopeService.resolveCurrent(null, overrideStationId));
    }

    /** 作用域视图（供前端 /me 展示与引导）。 */
    public StationViews.StationScopeView resolveView(Long overrideStationId) {
        InventoryScope.ScopeInfo info = inventoryScopeService.resolveCurrent(null, overrideStationId);
        return new StationViews.StationScopeView(overrideStationId, info.scopeLevel().name(), toAllowed(info));
    }

    private List<Long> toAllowed(InventoryScope.ScopeInfo info) {
        return switch (info.scopeLevel()) {
            case PLATFORM -> null; // 不限制
            case STATION -> List.of(info.effectiveStationId());
            case MANUFACTURER -> info.subordinateStationIds();
            default -> List.of(); // NONE / MERCHANT 等：无数据
        };
    }
}
