package com.claw.server.domain.inventory;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.InventoryViews;
import com.claw.server.common.enums.CustodyStatus;
import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.domain.consignment.ConsignmentCustody;
import com.claw.server.domain.consignment.ConsignmentCustodyRepository;
import com.claw.server.domain.credit.CreditLimitService;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.lifecycle.LifecycleEvent;
import com.claw.server.domain.lifecycle.LifecycleEventRepository;
import com.claw.server.domain.onboarding.OnboardingCreditBlock;
import com.claw.server.domain.org.OrgWritableGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 库存台账服务（增量 B · R3/B4）。
 *
 * <p>运营库存台账（inventory，每设备一行）与寄售占有权（consignment_custodies）1:1。
 * 本服务负责把设备从厂家自有库「发货至服务站」：建立寄售占有权 + 库存转为寄售在站（Q2 占有权转移点）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ConsignmentCustodyRepository custodyRepository;
    private final DeviceRepository deviceRepository;
    private final LifecycleEventRepository lifecycleRepository;
    /** 增量 C：入驻禁用守卫 + 授信额度校验（C1 硬阻断）。 */
    private final OrgWritableGuard orgWritableGuard;
    private final CreditLimitService creditLimitService;
    /** 模块三：库存统计只读聚合（JdbcClient）。 */
    private final InventoryStatsQuery statsQuery;

    /**
     * 发货至服务站：建立寄售占有权（consignment_custodies），库存转为寄售在站（Q2 占有权转移）。
     *
     * <p>增量 C 新增两道闸：
     * <ol>
     *   <li>{@code OrgWritableGuard} —— 服务站被平台禁用时禁止新货入站（Q7：只切新增）；</li>
     *   <li>{@code CreditLimitService} C1 —— 本次入站货值 + 已占用货值不得突破授信额度（硬阻断）。</li>
     * </ol>
     */
    @Transactional
    public void shipToStation(Long deviceId, Long stationId, Long manufacturerId, Long operatorId) {
        shipToStationBatch(List.of(deviceId), stationId, manufacturerId, operatorId);
    }

    /**
     * 批量发货至服务站（额度按<b>整批</b>校验，避免逐台放行后总量超标）。
     *
     * @throws BizException 40340 org.disabled.readonly（服务站已禁用）
     * @throws BizException 40941 credit.limit.exceeded（超出授信额度）
     * @throws BizException 40942 inventory.unit_value.required（货值无法解析，失败关闭不按 0 处理）
     */
    @Transactional
    public void shipToStationBatch(List<Long> deviceIds, Long stationId, Long manufacturerId, Long operatorId) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return;
        }
        // ① 禁用守卫：只切新增，不中断在途（Q7）
        orgWritableGuard.assertWritable(PrincipalType.STATION, stationId);
        // ② 入站时点货值快照（缺失则抛 40942，失败关闭）
        for (Long deviceId : deviceIds) {
            Inventory inv = inventoryRepository.findByDeviceId(deviceId)
                    .orElseThrow(() -> BizException.of(40401, "inventory.not.found"));
            creditLimitService.stampUnitValue(inv, null, null);
            inventoryRepository.save(inv);
        }
        // ③ C1 硬阻断：超出授信额度则落 onboarding_credit_blocks 并抛 40941
        creditLimitService.assertWithinLimit(stationId, deviceIds, OnboardingCreditBlock.Scene.CONSIGN_SHIP);
        for (Long deviceId : deviceIds) {
            shipOne(deviceId, stationId, manufacturerId, operatorId);
        }
    }

    /** 单台设备的实际发货动作（校验通过后执行）。 */
    private void shipOne(Long deviceId, Long stationId, Long manufacturerId, Long operatorId) {
        Inventory inv = inventoryRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> BizException.of(40401, "inventory.not.found"));
        // 取「当前」占有权：设备若经历过调拨，历史行已 ended_at 非空，
        // 无条件按 device_id 查会命中多行（部分唯一索引只保证未结束的行唯一）。
        ConsignmentCustody custody = custodyRepository.findByDeviceIdAndEndedAtIsNull(deviceId)
                .orElseGet(() -> custodyRepository.save(ConsignmentCustody.builder()
                        .deviceId(deviceId)
                        .manufacturerId(manufacturerId)
                        .holderStationId(stationId)
                        .status(CustodyStatus.ACTIVE)
                        .liabilityHolder("MANUFACTURER")
                        .build()));
        custody.setHolderStationId(stationId);
        custody.setManufacturerId(manufacturerId);
        custody.setStatus(CustodyStatus.ACTIVE);
        custody.setLiabilityHolder("MANUFACTURER");
        custody.setTransferredAt(null);
        custody.setTransferOrderId(null);
        custodyRepository.save(custody);

        inv.setOwnershipType(OwnershipType.CONSIGNED);
        inv.setHolderStationId(stationId);
        inv.setCustodyId(custody.getId());
        inv.setCurrentStatus(LifecycleStatus.AT_STATION);
        inv.setInboundAt(Instant.now());
        inv.setUpdatedAt(Instant.now());
        inventoryRepository.save(inv);

        deviceRepository.findById(deviceId).ifPresent(d -> {
            d.setLifecycleStatus(LifecycleStatus.AT_STATION.name());
            deviceRepository.save(d);
        });
        lifecycleRepository.save(LifecycleEvent.builder()
                .deviceId(deviceId)
                .fromStatus(LifecycleStatus.PRODUCING.name())
                .toStatus(LifecycleStatus.AT_STATION.name())
                .eventType("RECEIVE")
                .operatorId(operatorId)
                .stationId(stationId)
                .occurredAt(Instant.now())
                .build());
        log.info("设备 {} 发货至服务站 {}（建寄售占有权）", deviceId, stationId);
    }

    @Transactional(readOnly = true)
    public List<Inventory> listByManufacturer(Long manufacturerId) {
        return inventoryRepository.findByOwnerManufacturerId(manufacturerId);
    }

    @Transactional(readOnly = true)
    public List<Inventory> listByStation(Long stationId) {
        return inventoryRepository.findByHolderStationId(stationId);
    }

    @Transactional(readOnly = true)
    public List<Inventory> listByOwnership(OwnershipType ownershipType) {
        return inventoryRepository.findByOwnershipType(ownershipType);
    }

    @Transactional(readOnly = true)
    public Inventory getByDevice(Long deviceId) {
        return inventoryRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> BizException.of(40401, "inventory.not.found"));
    }

    // ===================== 模块三 · 角色作用域双视图 =====================

    /**
     * 角色作用域库存双视图（GET /me 取数）。取数前 Controller 已通过
     * {@code InventoryScopeService.resolveCurrent} 拿到 {@code ScopeInfo}，本方法不隐式读
     * {@code AuthContext}，作用域完全由入参决定，便于测试。
     *
     * <p>口径（A.3）：
     * <ul>
     *   <li>「现有库存」current：厂家/平台 = OWNED_BY_MFG；服务站 = CONSIGNED；
     *       可通过 filter.ownershipType 覆盖（管理员排障用）；</li>
     *   <li>「服务站库存」subordinateStations：厂家/平台 = CONSIGNED 按 holderStationId 分组；
     *       服务站/商家/未绑定 = 空；</li>
     *   <li>limit 截断 current 明细行（truncated 标记）；withRows=false 时 subordinateStations[].rows 为空数组。</li>
     * </ul>
     *
     * @param scope 作用域（由 InventoryScopeService 产出）
     * @param filter 筛选条件（status / ownershipType 已由 Controller 完成枚举校验）
     * @return 双视图
     */
    @Transactional(readOnly = true)
    public InventoryViews.InventoryScopeView listScoped(InventoryScope.ScopeInfo scope, InventoryViews.InventoryFilter filter) {
        OwnershipType overrideType = filter.ownershipType() == null ? null : OwnershipType.valueOf(filter.ownershipType());
        LifecycleStatus statusFilter = filter.status() == null ? null : LifecycleStatus.valueOf(filter.status());

        OwnershipType currentType = overrideType != null ? overrideType : defaultCurrentType(scope);
        List<Inventory> currentRows = fetchCurrent(scope, currentType);
        if (statusFilter != null) {
            currentRows = currentRows.stream().filter(i -> i.getCurrentStatus() == statusFilter).toList();
        }
        long currentTotal = currentRows.size();
        boolean currentTruncated = currentRows.size() > filter.limit();
        List<InventoryViews.InventoryRowView> current = currentRows.stream()
                .limit(filter.limit())
                .map(InventoryViewAssembler::toRowView)
                .toList();

        List<InventoryViews.StationGroupView> groups = fetchStationGroups(scope, statusFilter, filter.withRows(), filter.limit());
        long subordinateTotal = groups.stream().mapToLong(InventoryViews.StationGroupView::total).sum();

        return new InventoryViews.InventoryScopeView(
                InventoryViewAssembler.toScopeView(scope), current, currentTotal, currentTruncated, groups, subordinateTotal, null);
    }

    /**
     * 库存统计报表（GET /stats 取数）。作用域由入参决定。
     *
     * @param scope 作用域（由 InventoryScopeService 产出）
     * @return 统计视图
     */
    @Transactional(readOnly = true)
    public InventoryViews.InventoryStatsView statsOf(InventoryScope.ScopeInfo scope) {
        Long mfg = scope.effectiveManufacturerId();
        Long station = scope.effectiveStationId();

        List<InventoryViews.OwnershipCount> byOwn = statsQuery.countByOwnership(mfg, station);
        long ownedByMfg = sumOwn(byOwn, OwnershipType.OWNED_BY_MFG);
        long consigned = sumOwn(byOwn, OwnershipType.CONSIGNED);
        long full = sumOwn(byOwn, OwnershipType.FULL);
        long total = ownedByMfg + consigned + full;

        // total=0 时 consignedRatio 置 0，避免前端除零（C.3）
        double ratio = total == 0 ? 0.0 : (double) consigned / total;

        long atFactory = statsQuery.countAtFactory(mfg, station);
        List<InventoryViews.StatusCount> byStatus = statsQuery.countByStatus(mfg, station);
        List<InventoryViews.StationCount> byStation = statsQuery.countByStation(mfg, station);
        List<InventoryViews.ManufacturerCount> byManufacturer = statsQuery.countByManufacturer(mfg, station);

        return new InventoryViews.InventoryStatsView(
                scope.scopeLevel().name(),
                total,
                ownedByMfg,
                consigned,
                full,
                ratio,
                atFactory,
                byStation.size(),
                byManufacturer.size(),
                byStatus,
                byStation,
                byManufacturer,
                byOwn,
                Instant.now());
    }

    /** current 默认口径：服务站=CONSIGNED，其余（厂家/平台）=OWNED_BY_MFG。 */
    private OwnershipType defaultCurrentType(InventoryScope.ScopeInfo scope) {
        return scope.scopeLevel() == InventoryScope.ScopeLevel.STATION
                ? OwnershipType.CONSIGNED
                : OwnershipType.OWNED_BY_MFG;
    }

    /** 按作用域取 current 明细行。 */
    private List<Inventory> fetchCurrent(InventoryScope.ScopeInfo scope, OwnershipType type) {
        return switch (scope.scopeLevel()) {
            case PLATFORM -> inventoryRepository.findByOwnershipType(type);
            case STATION ->
                    inventoryRepository.findByHolderStationIdAndOwnershipType(scope.effectiveStationId(), type);
            case MANUFACTURER ->
                    inventoryRepository.findByOwnerManufacturerIdAndOwnershipType(scope.effectiveManufacturerId(), type);
            case MERCHANT, NONE -> List.of();
        };
    }

    /** 按作用域取 subordinateStations 分组（CONSIGNED 按 holderStationId 分组）。 */
    private List<InventoryViews.StationGroupView> fetchStationGroups(
            InventoryScope.ScopeInfo scope, LifecycleStatus statusFilter, boolean withRows, int limit) {
        if (scope.scopeLevel() == InventoryScope.ScopeLevel.STATION
                || scope.scopeLevel() == InventoryScope.ScopeLevel.MERCHANT
                || scope.scopeLevel() == InventoryScope.ScopeLevel.NONE) {
            return List.of();
        }

        List<Inventory> consigned;
        if (scope.scopeLevel() == InventoryScope.ScopeLevel.MANUFACTURER) {
            List<Long> stationIds = scope.subordinateStationIds();
            if (stationIds == null || stationIds.isEmpty()) {
                return List.of();
            }
            consigned = inventoryRepository.findByOwnerManufacturerIdAndOwnershipTypeAndHolderStationIdIn(
                    scope.effectiveManufacturerId(), OwnershipType.CONSIGNED, stationIds);
        } else {
            // PLATFORM：全平台 CONSIGNED
            consigned = inventoryRepository.findByOwnershipType(OwnershipType.CONSIGNED);
        }

        if (statusFilter != null) {
            consigned = consigned.stream().filter(i -> i.getCurrentStatus() == statusFilter).toList();
        }

        Map<Long, List<Inventory>> byStation = consigned.stream()
                .filter(i -> i.getHolderStationId() != null)
                .collect(Collectors.groupingBy(Inventory::getHolderStationId));

        List<InventoryViews.StationGroupView> groups = new ArrayList<>();
        for (Map.Entry<Long, List<Inventory>> e : byStation.entrySet()) {
            List<Inventory> rows = e.getValue();
            List<InventoryViews.StatusCount> byStatus = rows.stream()
                    .collect(Collectors.groupingBy(Inventory::getCurrentStatus, Collectors.counting()))
                    .entrySet().stream()
                    .map(en -> new InventoryViews.StatusCount(en.getKey().name(), en.getValue()))
                    .toList();
            List<InventoryViews.InventoryRowView> rowViews = withRows
                    ? rows.stream().limit(limit).map(InventoryViewAssembler::toRowView).toList()
                    : List.of();
            boolean truncated = withRows && rows.size() > limit;
            groups.add(new InventoryViews.StationGroupView(e.getKey(), rows.size(), byStatus, rowViews, truncated));
        }

        // 仅返回属于自身下属集合 / 指定站的分组（越权覆盖已在校验环节拦截）
        groups.sort((a, b) -> Long.compare(b.total(), a.total()));
        return groups;
    }

    private long sumOwn(List<InventoryViews.OwnershipCount> list, OwnershipType type) {
        return list.stream()
                .filter(c -> type.name().equals(c.ownershipType()))
                .mapToLong(InventoryViews.OwnershipCount::count)
                .sum();
    }
}
