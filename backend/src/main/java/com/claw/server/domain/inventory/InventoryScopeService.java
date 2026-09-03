package com.claw.server.domain.inventory;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.role.PermissionService;
import com.claw.server.domain.role.PrincipalResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 库存作用域解析（模块三 · M3-1/2/3）。
 *
 * <p>职责链：主体解析 → 作用域计算 → 越权校验 → 下属站点反查，
 * 产出 {@link InventoryScope.ScopeInfo} 交给 {@code InventoryService} 取数。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>单一职责</b>：鉴权语义与库存语义分离，{@code InventoryService} 只负责取数，
 *       不隐式读 {@code AuthContext}，便于测试与模块四复用；</li>
 *   <li><b>平台管理员</b>靠 {@code PermissionService.isPlatformAdmin} 判定，
 *       再经 {@code "*"} 通配命中控制器 {@code @RequirePermission}；</li>
 *   <li><b>下属服务站</b>按业务数据动态反查（"我的货现在寄在哪些站"），不新建任何表；</li>
 *   <li><b>越权覆盖失败关闭</b>：增强筛选入参与解析主体不符即抛
 *       {@code BizException 40301 inventory.scope.forbidden}（HTTP 403）；</li>
 *   <li><b>NONE 不抛 403</b>：未绑定主体（含 dev-open-access 下解析不出）返回空作用域，
 *       由前端显示引导文案，避免本地真实库联调白屏。</li>
 * </ul>
 *
 * 全部方法只读事务，对写链路零影响。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryScopeService {

    private final PrincipalResolver principalResolver;
    private final PermissionService permissionService;
    private final InventoryRepository inventoryRepository;

    /**
     * 解析当前登录账号的库存作用域。
     *
     * @param overrideManufacturerId 增强筛选：厂家 ID（null 表示不覆盖）
     * @param overrideStationId 增强筛选：服务站 ID（null 表示不覆盖）
     * @return 作用域值对象（永不返回 null）
     * @throws BizException 40301 inventory.scope.forbidden（越权覆盖被拒）
     */
    @Transactional(readOnly = true)
    public InventoryScope.ScopeInfo resolveCurrent(Long overrideManufacturerId, Long overrideStationId) {
        Long userId = AuthContext.currentUserId();
        boolean platformAdmin = userId != null && permissionService.isPlatformAdmin(userId);

        if (platformAdmin) {
            // 平台管理员：全平台，下属站点由取数逻辑按 CONSIGNED 全量分组得出。
            InventoryScope.ScopeInfo info = new InventoryScope.ScopeInfo(
                    PrincipalType.MANUFACTURER, null, false, true,
                    InventoryScope.ScopeLevel.PLATFORM, List.of(), null, null);
            assertOverrideAllowed(info, overrideManufacturerId, overrideStationId);
            return info;
        }

        List<PrincipalResolver.PrincipalRef> all = userId == null
                ? List.of()
                : principalResolver.resolveAll(userId);

        if (all.isEmpty()) {
            // 未绑定任何业务主体（含 dev-open-access 下解析不出）：NONE，不抛 403。
            return new InventoryScope.ScopeInfo(
                    null, null, false, false, InventoryScope.ScopeLevel.NONE, List.of(), null, null);
        }

        PrincipalResolver.PrincipalRef ref = pickPrimary(all);
        InventoryScope.ScopeLevel level = detectLevel(ref.type());
        List<Long> subs = subordinateStationIds(ref);
        Long effectiveMfg = ref.type() == PrincipalType.MANUFACTURER ? ref.principalId() : null;
        Long effectiveStation = ref.type() == PrincipalType.STATION ? ref.principalId() : null;

        InventoryScope.ScopeInfo info = new InventoryScope.ScopeInfo(
                ref.type(), ref.principalId(), ref.viaSubAccount(), false,
                level, subs, effectiveMfg, effectiveStation);
        assertOverrideAllowed(info, overrideManufacturerId, overrideStationId);
        return info;
    }

    /** 多绑定账号优先取 MANUFACTURER → STATION → MERCHANT，否则取首个。 */
    private PrincipalResolver.PrincipalRef pickPrimary(List<PrincipalResolver.PrincipalRef> all) {
        return all.stream().filter(r -> r.type() == PrincipalType.MANUFACTURER).findFirst()
                .or(() -> all.stream().filter(r -> r.type() == PrincipalType.STATION).findFirst())
                .or(() -> all.stream().filter(r -> r.type() == PrincipalType.MERCHANT).findFirst())
                .orElse(all.get(0));
    }

    /** 主体类型 → 作用域等级。 */
    private InventoryScope.ScopeLevel detectLevel(PrincipalType type) {
        return switch (type) {
            case MANUFACTURER -> InventoryScope.ScopeLevel.MANUFACTURER;
            case STATION -> InventoryScope.ScopeLevel.STATION;
            case MERCHANT -> InventoryScope.ScopeLevel.MERCHANT;
        };
    }

    /** 厂家下属服务站 = 「我的货现在寄在哪些站」（CONSIGNED 且 holderStationId 非空）。 */
    private List<Long> subordinateStationIds(PrincipalResolver.PrincipalRef ref) {
        if (ref.type() != PrincipalType.MANUFACTURER) {
            return List.of();
        }
        return inventoryRepository.findDistinctHolderStationIdsByManufacturer(ref.principalId(), OwnershipType.CONSIGNED);
    }

    /**
     * 越权覆盖校验（失败关闭）。
     *
     * <ul>
     *   <li>PLATFORM：任意值放行；</li>
     *   <li>STATION：stationId 只能等于自身；manufacturerId 仅作只读收窄，忽略（安全）；</li>
     *   <li>MANUFACTURER：manufacturerId 只能等于自身；stationId 必须属于自身下属站点集合；</li>
     *   <li>MERCHANT / NONE：不接受任何覆盖（无库存数据，覆盖无意义且可能越权）。</li>
     * </ul>
     *
     * @throws BizException 40301 inventory.scope.forbidden
     */
    private void assertOverrideAllowed(InventoryScope.ScopeInfo info,
                                        Long overrideManufacturerId, Long overrideStationId) {
        if (overrideManufacturerId == null && overrideStationId == null) {
            return;
        }
        switch (info.scopeLevel()) {
            case PLATFORM -> {
                // 平台管理员任意覆盖放行
            }
            case STATION -> {
                if (overrideStationId != null && !overrideStationId.equals(info.effectiveStationId())) {
                    throw BizException.of(40301, "inventory.scope.forbidden");
                }
                // manufacturerId 仅作只读收窄，可忽略
            }
            case MANUFACTURER -> {
                if (overrideManufacturerId != null && !overrideManufacturerId.equals(info.effectiveManufacturerId())) {
                    throw BizException.of(40301, "inventory.scope.forbidden");
                }
                if (overrideStationId != null
                        && (info.subordinateStationIds() == null
                            || !info.subordinateStationIds().contains(overrideStationId))) {
                    throw BizException.of(40301, "inventory.scope.forbidden");
                }
            }
            default -> throw BizException.of(40301, "inventory.scope.forbidden");
        }
    }
}
