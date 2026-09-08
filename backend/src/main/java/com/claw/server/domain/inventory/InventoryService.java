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
 *
 * <p><b>V82 变更（寄售入库发起方改造）</b>：寄售入库自 V82 起改由服务站自主发起
 * （{@code POST /api/v1/station/consignment/inbound} → {@link #stationConsignmentInbound}），
 * <b>厂家不再分拨到站</b>（{@code POST /api/v1/admin/inventory/ship} 已下线）。
 * 理由：厂家替服务站选站会把服务站数据暴露给厂家、且极易误操作 —— 「谁操作数据是谁的」。
 * 厂家侧只保留只读库存视图（{@code listScoped} / {@code statsOf}）。
 * 入库的写入语义（四处写入 + 授信硬阻断）未变，仍由 {@link #shipToStationBatch} → {@link #shipOne} 承担。
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

    // ===================== V82 · 服务站自主寄售入库 =====================

    /**
     * 服务站自主寄售入库（V82 · POST /api/v1/station/consignment/inbound 的领域入口）。
     *
     * <p>与已下线的厂家分拨（{@code POST /api/v1/admin/inventory/ship}）的唯一区别是
     * <b>发起方与两个 ID 的来源</b>：
     * <ul>
     *   <li>stationId 由登录站长的作用域带出（Controller 层 {@code StationScopeService#currentStationId}）；</li>
     *   <li>manufacturerId 由 {@code inventory.owner_manufacturer_id}（货权方，恒为厂家）带出，
     *       不来自入参 —— 货权不因入库动作发生任何转移。</li>
     * </ul>
     * 写入语义与旧分拨链路<b>完全一致</b>：本方法解析出货权方后直接复用
     * {@link #shipToStationBatch}，即 OrgWritableGuard 禁用守卫 + 货值快照 +
     * 授信额度硬阻断 + {@link #shipOne} 四处写入（占有权 / 库存 / 设备状态 / 生命周期事件）。
     *
     * @param deviceId 入库设备
     * @param stationId 占有站（当前登录站长自有站）
     * @param operatorId 操作人（可为 null，记生命周期事件用）
     * @return 入库结果（货权厂家 + 占有权 ID + 入站时点）
     * @throws BizException 40401 inventory.not.found（设备不在库存台账中）
     * @throws BizException 40943 inventory.owner_manufacturer.missing（台账缺货权方，无法入库）
     * @throws BizException 40944 inventory.custody.held.by.other.station（已被其它站占有）
     * @throws BizException 40945 inventory.custody.duplicate.inbound（本站重复入库）
     * @throws BizException 40941 credit.limit.exceeded（超出服务站授信额度）
     * @throws BizException 40340 org.disabled.readonly（服务站已被平台禁用）
     */
    @Transactional
    public InventoryViews.ConsignmentInboundResult stationConsignmentInbound(
            Long deviceId, Long stationId, Long operatorId) {
        Long manufacturerId = resolveOwnerManufacturerId(deviceId);
        assertCustodyInboundable(deviceId, stationId);
        shipToStationBatch(List.of(deviceId), stationId, manufacturerId, operatorId);

        Inventory saved = inventoryRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> BizException.of(40401, "inventory.not.found"));
        return new InventoryViews.ConsignmentInboundResult(
                deviceId, stationId, manufacturerId, saved.getCustodyId(), saved.getInboundAt());
    }

    /**
     * 取货权方：{@code inventory.owner_manufacturer_id} 恒为厂家，入库动作不改变货权。
     * 缺失即失败关闭 —— 宁可拒绝入库，也不允许产生一条无货权方的寄售占有权。
     */
    private Long resolveOwnerManufacturerId(Long deviceId) {
        Inventory inv = inventoryRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> BizException.of(40401, "inventory.not.found"));
        Long manufacturerId = inv.getOwnerManufacturerId();
        if (manufacturerId == null) {
            throw BizException.of(40943, "inventory.owner_manufacturer.missing");
        }
        return manufacturerId;
    }

    /**
     * 占有权占用校验（V82 新增，防止误操作与跨站抢货）。
     *
     * <p>旧分拨链路 {@link #shipOne} 允许覆盖写入（无条件改 holderStationId），那是"厂家
     * 主动分拨"的语义；改由服务站自主入库后，必须给出明确提示 —— 否则扫码重复提交会
     * 静默改掉别人的占有权。仅对 <b>ACTIVE 且已归属某站</b>的占有权拦截；占有权已结束
     * （TRANSFERRED_OUT / RETURNED / RELEASED）视为可重新入库（如回流后再次入站）。
     *
     * @throws BizException 40944 已被其它站占有
     * @throws BizException 40945 本站重复入库
     */
    private void assertCustodyInboundable(Long deviceId, Long stationId) {
        ConsignmentCustody current = custodyRepository.findByDeviceIdAndEndedAtIsNull(deviceId).orElse(null);
        if (current == null
                || current.getStatus() != CustodyStatus.ACTIVE
                || current.getHolderStationId() == null) {
            return;
        }
        if (stationId.equals(current.getHolderStationId())) {
            throw BizException.of(40945, "inventory.custody.duplicate.inbound");
        }
        throw BizException.of(40944, "inventory.custody.held.by.other.station", current.getHolderStationId());
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
