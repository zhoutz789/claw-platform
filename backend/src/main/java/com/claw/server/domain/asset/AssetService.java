package com.claw.server.domain.asset;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.dto.AssetRequests;
import com.claw.server.common.dto.AssetRequests.*;
import com.claw.server.common.enums.AclRelation;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.domain.role.PermissionService;
import com.claw.server.domain.role.RoleGrantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

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
    private final UserAssetsAclRepository aclRepository;
    private final PermissionService permissionService;
    private final RoleGrantService roleGrantService;

    @Transactional
    public ApiViews.AssetView createVehicle(CreateVehicle req, Long operatorId) {
        Asset asset = newAsset(AssetType.VEHICLE, req.assetNo(), req.qrCode(),
                req.ownerId() != null ? req.ownerId() : operatorId);
        asset = assetRepository.save(asset);
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
        Asset asset = newAsset(AssetType.BATTERY, req.assetNo(), req.qrCode(), operatorId);
        asset = assetRepository.save(asset);
        Battery b = Battery.builder()
                .assetId(asset.getId())
                .model(req.model())
                .capacityKwh(req.capacityKwh())
                .protocolVer(req.protocolVer())
                .soh(req.soh() != null ? req.soh() : java.math.BigDecimal.valueOf(100.00))
                .depositValue(req.depositValue() != null ? req.depositValue() : java.math.BigDecimal.ZERO)
                .build();
        batteryRepository.save(b);
        return toView(asset);
    }

    @Transactional(readOnly = true)
    public List<ApiViews.AssetView> listAssets(AssetType assetType, AssetStatus status) {
        List<Asset> list;
        if (assetType != null && status != null) {
            list = assetRepository.findByAssetTypeAndStatus(assetType, status);
        } else if (assetType != null) {
            list = assetRepository.findAll().stream().filter(a -> a.getAssetType() == assetType).toList();
        } else if (status != null) {
            list = assetRepository.findAll().stream().filter(a -> a.getStatus() == status).toList();
        } else {
            list = assetRepository.findAll();
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

    /** 状态流转：经状态机校验 + 审计留痕。 */
    @Transactional
    public ApiViews.AssetView changeStatus(Long assetId, ChangeStatus req, Long operatorId) {
        Asset asset = load(assetId);
        requireManage(asset, operatorId);
        AssetStatus from = asset.getStatus();
        AssetStateMachine.assertTransition(from, req.toStatus());
        asset.setStatus(req.toStatus());
        asset.setUpdatedAt(Instant.now());
        assetRepository.save(asset);
        statusLogRepository.save(AssetStatusLog.builder()
                .assetId(assetId)
                .fromStatus(from.name())
                .toStatus(req.toStatus().name())
                .operatorId(operatorId)
                .reason(req.reason())
                .build());
        log.info("资产 {} 状态 {} → {}", assetId, from, req.toStatus());
        return toView(asset);
    }

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
                a.getOwnerId(), a.getUserId(), a.getStatus(), a.getCreatedAt());
    }
}
