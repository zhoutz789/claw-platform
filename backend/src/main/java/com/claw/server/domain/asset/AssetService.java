package com.claw.server.domain.asset;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.dto.AssetRequests;
import com.claw.server.common.dto.AssetRequests.*;
import com.claw.server.common.enums.AclRelation;
import com.claw.server.common.enums.AssetLifecycleStage;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.TransferType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.DataScope;
import com.claw.server.common.security.DataScopeContext;
import com.claw.server.common.security.DataScopeFieldMapping;
import com.claw.server.common.security.DataScopeResult;
import com.claw.server.common.security.DataScopeSpec;
import com.claw.server.domain.custody.CustodyService;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.iot.Telemetry;
import com.claw.server.domain.iot.TelemetryRepository;
import com.claw.server.domain.role.DataScopeService;
import com.claw.server.domain.role.PermissionService;
import com.claw.server.domain.role.RoleGrantService;
import com.claw.server.domain.user.Department;
import com.claw.server.domain.user.User;
import com.claw.server.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 资产域服务：车辆/电池 CRUD、状态机流转（带审计）、资产级 ACL。
 *
 * <p>关键约束（技术文档 2.1）：
 * <ul>
 *   <li>所有状态变更经 {@link AssetStateMachine} 校验，并写入 {@link AssetStatusLog}；</li>
 *   <li>敏感操作（改状态/设 ACL）要求对资产具 MANAGE 权限或平台管理员（三层权限）；</li>
 *   <li>购车登记 owner 时自动授予 OWNER 角色包 + MANAGE ACL（人人经济：购车即车主）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssetService {

    private final AssetRepository assetRepository;
    private final VehicleRepository vehicleRepository;
    private final BatteryRepository batteryRepository;
    private final AssetStatusLogRepository statusLogRepository;
    private final AssetLifecycleEventRepository lifecycleEventRepository;
    private final UserAssetsAclRepository aclRepository;
    private final PermissionService permissionService;
    private final RoleGrantService roleGrantService;
    private final DataScopeService dataScopeService;
    private final UserRepository userRepository;
    private final CustodyService custodyService;
    private final DeviceRepository deviceRepository;
    private final DroneRepository droneRepository;
    private final TelemetryRepository telemetryRepository;
    private final AssetMaintenanceRecordRepository maintenanceRecordRepository;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Transactional
    public ApiViews.AssetView createVehicle(CreateVehicle req, Long operatorId) {
        Asset asset = newAsset(AssetType.VEHICLE, req.assetNo(), req.qrCode(),
                req.ownerId() != null ? req.ownerId() : operatorId);
        asset = assetRepository.save(asset);
        recordLifecycle(asset.getId(), AssetLifecycleStage.PRODUCED, operatorId, null, "资产建档");
        Vehicle v = Vehicle.builder()
                .assetId(asset.getId())
                .model(req.model())
                .vin(req.vin())
                .frameNo(req.frameNo())
                .motorNo(req.motorNo())
                .contractType(req.contractType())
                .lessorId(req.lessorId())
                .protocolVer(req.protocolVer())
                .build();
        vehicleRepository.save(v);

        Long ownerId = req.ownerId() != null ? req.ownerId() : operatorId;
        grantOwnership(ownerId, asset.getId());
        return toView(asset);
    }

    @Transactional
    public ApiViews.AssetView createBattery(CreateBattery req, Long operatorId) {
        Long ownerId = req.ownerId() != null ? req.ownerId() : operatorId;
        Asset asset = newAsset(AssetType.BATTERY, req.assetNo(), req.qrCode(), ownerId);
        asset = assetRepository.save(asset);
        recordLifecycle(asset.getId(), AssetLifecycleStage.PRODUCED, operatorId, null, "资产建档");
        Battery b = Battery.builder()
                .assetId(asset.getId())
                .model(req.model())
                .capacityKwh(req.capacityKwh())
                .protocolVer(req.protocolVer())
                .soh(req.soh() != null ? req.soh() : java.math.BigDecimal.valueOf(100.00))
                .depositValue(req.depositValue() != null ? req.depositValue() : java.math.BigDecimal.ZERO)
                .build();
        batteryRepository.save(b);
        grantOwnership(ownerId, asset.getId());
        return toView(asset);
    }

    @Transactional
    public ApiViews.AssetView createDrone(CreateDrone req, Long operatorId) {
        Asset asset = newAsset(AssetType.DRONE, req.assetNo(), req.qrCode(),
                req.ownerId() != null ? req.ownerId() : operatorId);
        asset = assetRepository.save(asset);
        recordLifecycle(asset.getId(), AssetLifecycleStage.PRODUCED, operatorId, null, "无人机建档");
        Drone d = Drone.builder()
                .assetId(asset.getId())
                .remoteId(req.remoteId())
                .model(req.model())
                .maxFlightTimeMin(req.maxFlightTimeMin())
                .maxPayloadKg(req.maxPayloadKg())
                .payloadType(req.payloadType())
                .airworthinessCertNo(req.airworthinessCertNo())
                .pilotLicenseNo(req.pilotLicenseNo())
                .protocolVer(req.protocolVer())
                .flightMinutes(0L)
                .build();
        droneRepository.save(d);

        Long ownerId = req.ownerId() != null ? req.ownerId() : operatorId;
        grantOwnership(ownerId, asset.getId());
        return toView(asset);
    }

    @DataScope(entity = "asset")
    @Transactional(readOnly = true)
    public List<ApiViews.AssetView> listAssets(AssetType assetType, AssetStatus status) {
        // 数据范围 enforcement（P1-T03）：DataScopeAspect 写入 DataScopeContext；
        // 若非经切面进入（同 Service 内部调用）则降级直接解析，保证过滤不丢。
        DataScopeResult ds = DataScopeContext.get();
        if (ds == null) {
            ds = dataScopeService.resolve(AuthContext.currentUserId());
        }
        Specification<Asset> spec = (ds == null || ds.isAll())
                ? (root, q, cb) -> cb.conjunction()
                : DataScopeSpec.of(DataScopeFieldMapping.of("ownerId", null, null, "assetType",
                        User.class, Department.class)).apply(ds);
        List<Asset> list = assetRepository.findAll(spec);
        if (assetType != null) {
            list = list.stream().filter(a -> a.getAssetType() == assetType).toList();
        }
        if (status != null) {
            list = list.stream().filter(a -> a.getStatus() == status).toList();
        }
        return list.stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ApiViews.AssetView getAsset(Long id) {
        return toView(load(id));
    }

    /** 设置资产 ACL（MANAGE/USE/LEASE）。要求对资产具 MANAGE 或平台管理员。 */
    @Transactional
    public void setAcl(Long assetId, SetAcl req, Long operatorId) {
        Asset asset = load(assetId);
        requireManage(asset, operatorId);
        aclRepository.findByUserIdAndAssetIdAndRelation(req.userId(), assetId, req.relation())
                .ifPresentOrElse(
                        acl -> { /* 已存在，幂等 */ },
                        () -> aclRepository.save(UserAssetsAcl.builder()
                                .userId(req.userId())
                                .assetId(assetId)
                                .relation(req.relation())
                                .grantedBy(operatorId)
                                .build()));
    }

    /** 状态流转：经状态机校验 + 审计留痕 + 生命周期事件。 */
    @Transactional
    public ApiViews.AssetView changeStatus(Long assetId, ChangeStatus req, Long operatorId) {
        Asset asset = load(assetId);
        requireManage(asset, operatorId);
        applyTransition(asset, req.toStatus(), operatorId, req.reason(), null);
        return toView(asset);
    }

    /**
     * 设备上线部署（绑定到站点/产权人）。上线即写入产权链首笔（DEPLOY），
     * 并把资产从在库推到使用中、记录 IN_USE 生命周期事件、确保 IoT 设备行存在。
     * 幂等：已 IN_USE 时跳过状态变更，仅补登缺失的产权链/设备行。
     */
    @Transactional
    public ApiViews.AssetView bindDevice(BindDevice req, Long operatorId) {
        Asset asset = load(req.assetId());
        requireManage(asset, operatorId);

        if (asset.getStatus() == AssetStatus.IN_STOCK) {
            applyTransition(asset, AssetStatus.IN_USE, operatorId, "设备上线部署", req.location());
        }

        // 产权链首笔（DEPLOY）：平台/厂家 → 当前产权人
        Long ownerId = asset.getOwnerId() != null ? asset.getOwnerId() : operatorId;
        custodyService.recordTransfer(asset.getId(), asset.getAssetType().name(),
                null, ownerId, TransferType.DEPLOY, req.stationId(),
                null, readSoh(asset.getId()), null, readCycleCount(asset.getId()));

        // 确保 IoT 设备行存在（数字孪生/遥测前置条件）
        if (deviceRepository.findByAssetId(asset.getId()).isEmpty()) {
            String devType = deriveDeviceType(asset.getAssetType(), req.deviceType());
            if (devType != null) {
                deviceRepository.save(Device.builder()
                        .assetId(asset.getId())
                        .deviceType(devType)
                        .imei(req.imei())
                        .status("ACTIVE")
                        .build());
            }
        }
        log.info("设备上线部署 assetId={} station={} owner={}", asset.getId(), req.stationId(), ownerId);
        return toView(asset);
    }

    /** 查询资产完整生命周期轨迹（闭环溯源）。 */
    @Transactional(readOnly = true)
    public List<AssetLifecycleEvent> getLifecycle(Long assetId) {
        return lifecycleEventRepository.findByAssetIdOrderByOccurredAtDesc(assetId);
    }

    /**
     * 生命周期扫描器调用：系统级状态推进（绕过 MANAGE 权限检查）。
     * 仅允许推进到 RETIRED / RECYCLED（闭环终点），用于 SOH/年限自动退役与回收。
     */
    @Transactional
    public void autoTransition(Long assetId, AssetStatus toStatus, String reason) {
        Asset asset = load(assetId);
        if (!AssetStateMachine.canTransition(asset.getStatus(), toStatus)) {
            log.debug("跳过资产 {} 非法自动流转 {}→{}", assetId, asset.getStatus(), toStatus);
            return;
        }
        applyTransition(asset, toStatus, SYSTEM_USER, reason, null);
        if (toStatus == AssetStatus.RECYCLED) {
            // 进入回收 → 触发残值/梯次利用流程（recovery 域在 Phase2 兑现收益，此处先落产权链记录）
            Long ownerId = asset.getOwnerId() != null ? asset.getOwnerId() : SYSTEM_USER;
            custodyService.recordTransfer(asset.getId(), asset.getAssetType().name(),
                    ownerId, ownerId, TransferType.RECOVERY, null,
                    null, readSoh(asset.getId()), null, readCycleCount(asset.getId()));
        }
    }

    /**
     * 转让至资产大厅：将资产置为公开可见（LISTED），经状态机校验 + 审计留痕。
     * 由项目管理域 ProjectService 在 TRANSFER 授权时调用（跨域走服务接口，不直持 Repository）。
     *
     * @param assetId    资产 ID
     * @param operatorId 操作人（须对资产具 MANAGE 或所有权）
     * @return 资产视图
     */
    @Transactional
    public ApiViews.AssetView listInHall(Long assetId, Long operatorId) {
        Asset asset = load(assetId);
        requireManage(asset, operatorId);
        applyTransition(asset, AssetStatus.LISTED, operatorId, "转让至资产大厅·公开可见", null);
        return toView(asset);
    }

    /**
     * 从订单「逐台登记」生成资产（V38 资产闭环入口）。
     *
     * <p>建主资产（带 productId/skuId/manufacturerId/serialNumber/ownerId/orderItemId，状态 IN_STOCK）
     * + 按 assetType 建扩展（vehicle 拷 vin/frame/motor；drone 拷 remoteId/model；battery 拷 model）
     * + 生命周期 PRODUCED + 建立产权（grantOwnership，人人经济：资产诞生即归属买家）。
     * 产权台账（AssetOwnership）由订单域 {@code UnitRegistrationService} 经 SharedPoolService 建立，
     * 本方法只负责资产侧建档与 ACL。
     *
     * @param req        登记请求（含类型标识 vin/frame/motor/remoteId/model 等）
     * @param operatorId 操作人（厂家登记台）
     * @return 资产视图（含新生成 assetId）
     */
    @Transactional
    public ApiViews.AssetView provisionFromRegistration(ProvisionAssetReq req, Long operatorId) {
        if (req.qrCode() == null || req.qrCode().isBlank()) {
            throw BizException.invalidParam("error.asset.qr.required");
        }
        AssetType type = AssetType.valueOf(req.assetType());
        String assetNo = (req.assetNo() != null && !req.assetNo().isBlank())
                ? req.assetNo() : generateAssetNo();
        String qrCode = req.qrCode();
        Long ownerId = req.ownerId() != null ? req.ownerId() : operatorId;

        ApiViews.AssetView view;
        switch (type) {
            case VEHICLE, EV -> {
                CreateVehicle cv = new CreateVehicle(assetNo, qrCode, defaultStr(req.model(), "UNKNOWN"),
                        req.vin(), req.frameNo(), req.motorNo(), null, null, null, ownerId);
                view = createVehicle(cv, operatorId);
            }
            case DRONE -> {
                CreateDrone cd = new CreateDrone(assetNo, qrCode,
                        defaultStr(req.remoteId(), generateRemoteId()), defaultStr(req.model(), "UNKNOWN"),
                        null, null, null, null, null, null, ownerId);
                view = createDrone(cd, operatorId);
            }
            case BATTERY -> {
                CreateBattery cb = new CreateBattery(assetNo, qrCode, defaultStr(req.model(), "UNKNOWN"),
                        req.capacityKwh() != null ? req.capacityKwh() : BigDecimal.ZERO, null, null, null,
                        ownerId);
                view = createBattery(cb, operatorId);
            }
            default -> {
                // CHARGER / PV_STATION 等无专属扩展工厂：建基础资产 + 生命周期 + 产权
                Asset base = newAsset(type, assetNo, qrCode, ownerId);
                base = assetRepository.save(base);
                recordLifecycle(base.getId(), AssetLifecycleStage.PRODUCED, operatorId, null, "资产建档");
                grantOwnership(ownerId, base.getId());
                view = toView(base);
            }
        }

        // 溯源字段 + 当前主部件快照（设计 §2.3 / §2.4）
        Asset asset = assetRepository.findById(view.id())
                .orElseThrow(() -> BizException.notFound("error.asset.not.found"));
        asset.setProductId(req.productId());
        asset.setSkuId(req.skuId());
        asset.setManufacturerId(req.manufacturerId());
        asset.setSerialNumber(req.serialNumber());
        asset.setOrderItemId(req.orderItemId());
        // 设计 §9.3：资产生成即归属买家，杜绝台账/资产/ACL 三方不一致
        asset.setOwnerId(ownerId);
        asset.setComponentNosJson(req.componentNosJson());
        assetRepository.save(asset);
        log.info("订单登记生成资产 assetId={} orderItemId={} assetType={} owner={}",
                asset.getId(), req.orderItemId(), type, ownerId);
        return toView(asset);
    }

    /**
     * 主部件更换留痕（F7.4 / F16.5）：更新 assets.component_nos_json 对应项 + 写 asset_maintenance_records。
     *
     * <p>按 componentType 在快照中定位旧编号并替换为新编号；若不存在则追加一项。
     * 维修记录写入 component_type / old_component_no / new_component_no，供「按电机号/电池号查更换」检索。
     */
    @Transactional
    public void replaceComponent(Long assetId, String componentType, String oldComponentNo,
                                 String newComponentNo, Long operatorId) {
        Asset asset = load(assetId);
        requireManage(asset, operatorId);

        List<ComponentNo> list = parseComponentNos(asset.getComponentNosJson());
        boolean found = false;
        for (ComponentNo c : list) {
            if (c.type != null && c.type.equalsIgnoreCase(componentType)) {
                c.no = newComponentNo;
                found = true;
                break;
            }
        }
        if (!found) {
            list.add(new ComponentNo(componentType, newComponentNo));
        }
        asset.setComponentNosJson(toJson(list));
        assetRepository.save(asset);

        AssetMaintenanceRecord rec = AssetMaintenanceRecord.builder()
                .assetId(assetId)
                .servicedAt(Instant.now())
                .mtype("COMPONENT_REPLACE")
                .componentType(componentType)
                .oldComponentNo(oldComponentNo)
                .newComponentNo(newComponentNo)
                .build();
        maintenanceRecordRepository.save(rec);
        log.info("主部件更换 assetId={} type={} old={} new={}", assetId, componentType, oldComponentNo, newComponentNo);
    }

    /** 资产编号自动生成（唯一，规避与既有资产冲突）。 */
    private String generateAssetNo() {
        return "ASSET-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private String generateRemoteId() {
        return "RMT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 16);
    }

    private static String defaultStr(String v, String fallback) {
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    /** component_nos_json 解析（结构见设计 §2.4）。 */
    private List<ComponentNo> parseComponentNos(String json) {
        if (json == null || json.isBlank()) {
            return new java.util.ArrayList<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<ComponentNo>>() {
            });
        } catch (Exception e) {
            log.warn("component_nos_json 解析失败，重置为空列表：{}", json);
            return new java.util.ArrayList<>();
        }
    }

    private String toJson(List<ComponentNo> list) {
        try {
            return JSON.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("component_nos_json 序列化失败");
            return "[]";
        }
    }

    /** component_nos_json 单项（type/no）。 */
    static class ComponentNo {
        public String type;
        public String no;

        public ComponentNo() {
        }

        public ComponentNo(String type, String no) {
            this.type = type;
            this.no = no;
        }
    }

    private static final Long SYSTEM_USER = -1L;

    /**
     * 开通资产功能（建子账户）。子账户属账户域（S2 实现），此处仅校验资产存在并返回受理说明。
     */
    @Transactional(readOnly = true)
    public String openFunction(Long assetId, Long operatorId) {
        Asset asset = load(assetId);
        requireManage(asset, operatorId);
        return "ACCEPTED_S2_LEDGER"; // 功能子账户将在 S2 账户域创建
    }

    // ---- 内部工具 ----

    private void grantOwnership(Long ownerId, Long assetId) {
        roleGrantService.grantByEvent(ownerId, "OWNER");
        if (!aclRepository.existsByUserIdAndAssetIdAndRelation(ownerId, assetId, AclRelation.MANAGE)) {
            aclRepository.save(UserAssetsAcl.builder()
                    .userId(ownerId)
                    .assetId(assetId)
                    .relation(AclRelation.MANAGE)
                    .grantedBy(ownerId)
                    .build());
        }
    }

    private void requireManage(Asset asset, Long operatorId) {
        boolean ok = permissionService.isPlatformAdmin(operatorId)
                || asset.getOwnerId() != null && asset.getOwnerId().equals(operatorId)
                || permissionService.hasAssetPermission(operatorId, asset.getId(), AclRelation.MANAGE);
        if (!ok) {
            throw BizException.of(BizException.NOT_FOUND + 10, "error.asset.no.permission");
        }
    }

    /** 统一状态流转：状态机校验 + 资产表更新 + 状态审计 + 生命周期事件（闭环留痕）。 */
    private void applyTransition(Asset asset, AssetStatus to, Long operatorId, String reason, String location) {
        AssetStatus from = asset.getStatus();
        AssetStateMachine.assertTransition(from, to);
        asset.setStatus(to);
        asset.setUpdatedAt(Instant.now());
        assetRepository.save(asset);
        statusLogRepository.save(AssetStatusLog.builder()
                .assetId(asset.getId())
                .fromStatus(from.name())
                .toStatus(to.name())
                .operatorId(operatorId)
                .reason(reason)
                .build());
        AssetLifecycleStage stage = toLifecycleStage(to);
        if (stage != null) {
            recordLifecycle(asset.getId(), stage, operatorId, location, reason);
        }
        log.info("资产 {} 状态 {} → {} ({})", asset.getId(), from, to, reason);
    }

    /** 记录生命周期事件（资产闭环轨迹）。 */
    private void recordLifecycle(Long assetId, AssetLifecycleStage stage, Long operatorId,
                                 String location, String note) {
        lifecycleEventRepository.save(AssetLifecycleEvent.builder()
                .assetId(assetId)
                .stage(stage)
                .operatorId(operatorId)
                .location(location)
                .note(note)
                .build());
    }

    /** 运营态 → 生命周期阶段映射（仅关键节点落事件，IN_STOCK/SHARED/DISABLED 不单独记事件）。 */
    private AssetLifecycleStage toLifecycleStage(AssetStatus status) {
        return switch (status) {
            case IN_USE -> AssetLifecycleStage.IN_USE;
            case REPAIR -> AssetLifecycleStage.MAINTENANCE;
            case RETIRED -> AssetLifecycleStage.RETIRED;
            case RECYCLED -> AssetLifecycleStage.RECYCLED;
            case SCRAPPED -> AssetLifecycleStage.DESTROYED;
            default -> null;
        };
    }

    private java.math.BigDecimal readSoh(Long assetId) {
        // 优先取遥测快照（无人机/整车通用），缺失回退电池 SOH
        java.math.BigDecimal t = telemetryRepository.findByAssetId(assetId)
                .map(Telemetry::getSoh).orElse(null);
        if (t != null) {
            return t;
        }
        return batteryRepository.findByAssetId(assetId)
                .map(com.claw.server.domain.asset.Battery::getSoh).orElse(null);
    }

    private Integer readCycleCount(Long assetId) {
        return batteryRepository.findByAssetId(assetId)
                .map(com.claw.server.domain.asset.Battery::getCycleCount).orElse(null);
    }

    /** 按资产类型推导默认设备类型（用于补建 IoT 设备行）。PV_STATION 无车载终端，返回 null。 */
    private String deriveDeviceType(AssetType type, String override) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        return switch (type) {
            case VEHICLE -> "VEHICLE_TCU";
            case BATTERY -> "BATTERY_BMS";
            case CHARGER -> "CHARGER";
            case DRONE -> "DRONE_FCU";
            default -> null;
        };
    }

    private Asset newAsset(AssetType type, String assetNo, String qrCode, Long ownerId) {
        if (assetRepository.findByAssetNo(assetNo).isPresent()) {
            throw BizException.invalidParam("error.asset.no.duplicate");
        }
        return Asset.builder()
                .assetType(type)
                .assetNo(assetNo)
                .qrCode(qrCode)
                .ownerId(ownerId)
                .status(AssetStatus.IN_STOCK)
                .build();
    }

    private Asset load(Long id) {
        return assetRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("error.asset.not.found"));
    }

    private ApiViews.AssetView toView(Asset a) {
        return new ApiViews.AssetView(a.getId(), a.getAssetType(), a.getAssetNo(), a.getQrCode(),
                a.getSerialNumber(), a.getManufacturerId(), a.getProductId(), a.getSkuId(),
                a.getOwnerId(), a.getUserId(), a.getStatus(), a.getCreatedAt());
    }
}
