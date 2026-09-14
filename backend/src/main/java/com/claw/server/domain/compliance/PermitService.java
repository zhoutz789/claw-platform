package com.claw.server.domain.compliance;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.PermitStatus;
import com.claw.server.domain.asset.AssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 空域许可服务（V143）：签发 / 吊销 / 有效性判定 / 任务绑定。
 *
 * <p>有效性判定 {@link #validPermitFor} 是 {@link PermitGate} 第③步的唯一数据源，
 * 规则：{@code status=ACTIVE} 且 {@code valid_from <= at <= valid_to}，
 * 且省域覆盖（省域未知时视为「不限省域」放行判定，由④步省域校验兜底）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermitService {

    private final DroneAirspacePermitRepository permitRepository;
    private final DroneMissionPermitBindingRepository bindingRepository;
    private final AssetRepository assetRepository;

    /**
     * 签发空域许可（落库即 ACTIVE）。
     *
     * @param assetId       无人机 asset_id
     * @param permitNo      许可证号（唯一）
     * @param issuer        签发机构（空则 SSCA）
     * @param scopeProvince 覆盖省域（可空 = 不限）
     * @param validFrom     生效起点（必填）
     * @param validTo       生效终点（必填，须晚于起点）
     * @param docRef        文书引用（可空）
     * @return 已签发的许可
     * @throws BizException 10001 error.drone.permit.no.invalid / error.drone.permit.window.invalid
     * @throws BizException 40466 error.drone.not.found（资产不存在）
     * @throws BizException 40968 error.drone.permit.duplicate（许可证号已存在）
     */
    @Transactional
    public DroneAirspacePermit issue(Long assetId, String permitNo, String issuer, String scopeProvince,
                                     Instant validFrom, Instant validTo, String docRef) {
        if (assetId == null || permitNo == null || permitNo.isBlank()) {
            throw BizException.invalidParam("error.drone.permit.no.invalid");
        }
        if (validFrom == null || validTo == null || !validTo.isAfter(validFrom)) {
            throw BizException.invalidParam("error.drone.permit.window.invalid");
        }
        if (assetRepository.findById(assetId).isEmpty()) {
            throw BizException.of(40466, "error.drone.not.found", assetId);
        }
        String no = permitNo.trim();
        if (permitRepository.existsByPermitNo(no)) {
            throw BizException.of(40968, "error.drone.permit.duplicate", no);
        }

        DroneAirspacePermit permit = DroneAirspacePermit.builder()
                .assetId(assetId)
                .permitNo(no)
                .issuer(issuer != null && !issuer.isBlank() ? issuer.trim() : "SSCA")
                .scopeProvince(scopeProvince != null && !scopeProvince.isBlank() ? scopeProvince.trim() : null)
                .validFrom(validFrom)
                .validTo(validTo)
                .status(PermitStatus.ACTIVE)
                .docRef(docRef)
                .build();
        DroneAirspacePermit saved = permitRepository.save(permit);
        log.info("签发空域许可 asset={} permitNo={} province={} window=[{},{}]",
                assetId, no, saved.getScopeProvince(), validFrom, validTo);
        return saved;
    }

    /**
     * 吊销许可（ACTIVE → REVOKED，只改状态不删除，保留可审计历史）。
     *
     * @param permitId 许可 id
     * @return 更新后的许可
     * @throws BizException 40472 error.drone.permit.not.found（许可不存在）
     * @throws BizException 40969 error.drone.permit.status.invalid（非 ACTIVE 不可吊销）
     */
    @Transactional
    public DroneAirspacePermit revoke(Long permitId) {
        DroneAirspacePermit permit = requirePermit(permitId);
        if (permit.getStatus() != PermitStatus.ACTIVE) {
            throw BizException.of(40969, "error.drone.permit.status.invalid", permit.getStatus().name());
        }
        permit.setStatus(PermitStatus.REVOKED);
        permit.setUpdatedAt(Instant.now());
        DroneAirspacePermit saved = permitRepository.save(permit);
        log.info("吊销空域许可 permitNo={} id={}", permit.getPermitNo(), permitId);
        return saved;
    }

    /**
     * 取某资产在某省域、某时刻的有效许可。
     *
     * @param assetId  无人机 asset_id
     * @param province 作业省域（可空 = 不限省域判定）
     * @param at       判定时刻
     * @return 命中的有效许可；无则 empty
     */
    @Transactional(readOnly = true)
    public Optional<DroneAirspacePermit> validPermitFor(Long assetId, String province, Instant at) {
        if (assetId == null) {
            return Optional.empty();
        }
        return permitRepository.findByAssetIdAndStatusAndDeletedFalse(assetId, PermitStatus.ACTIVE)
                .stream()
                .filter(p -> !at.isBefore(p.getValidFrom()) && !at.isAfter(p.getValidTo()))
                .filter(p -> coversProvince(p, province))
                .max(Comparator.comparing(DroneAirspacePermit::getValidFrom));
    }

    /**
     * 把任务/架次绑定到许可（留痕，形成可审计链路）。
     *
     * @param permitId       许可 id
     * @param taskId         任务大厅任务 id（与 droneMissionId 至少一个非空）
     * @param droneMissionId 作业计量记录 id
     * @return 绑定记录
     * @throws BizException 40472 error.drone.permit.not.found（许可不存在）
     * @throws BizException 40969 error.drone.permit.status.invalid（许可非 ACTIVE）
     * @throws BizException 10001 error.drone.mission.permit.binding.invalid（taskId/missionId 均为空）
     */
    @Transactional
    public DroneMissionPermitBinding bindMission(Long permitId, Long taskId, Long droneMissionId) {
        DroneAirspacePermit permit = requirePermit(permitId);
        if (permit.getStatus() != PermitStatus.ACTIVE) {
            throw BizException.of(40969, "error.drone.permit.status.invalid", permit.getStatus().name());
        }
        if (taskId == null && droneMissionId == null) {
            throw BizException.invalidParam("error.drone.mission.permit.binding.invalid");
        }
        DroneMissionPermitBinding binding = DroneMissionPermitBinding.builder()
                .permitId(permitId)
                .taskId(taskId)
                .droneMissionId(droneMissionId)
                .boundAt(Instant.now())
                .build();
        DroneMissionPermitBinding saved = bindingRepository.save(binding);
        log.info("绑定许可 permitNo={} taskId={} missionId={}",
                permit.getPermitNo(), taskId, droneMissionId);
        return saved;
    }

    /**
     * 许可列表（可按资产过滤）。
     *
     * @param assetId 资产 id（可空 = 全部）
     * @return 许可列表（按签发时间倒序）
     */
    @Transactional(readOnly = true)
    public List<DroneAirspacePermit> list(Long assetId) {
        if (assetId == null) {
            return permitRepository.findByDeletedFalseOrderByCreatedAtDesc();
        }
        return permitRepository.findByAssetIdAndDeletedFalseOrderByCreatedAtDesc(assetId);
    }

    /** 某许可的绑定记录（按绑定时间倒序）。 */
    @Transactional(readOnly = true)
    public List<DroneMissionPermitBinding> bindings(Long permitId) {
        return bindingRepository.findByPermitIdAndDeletedFalseOrderByBoundAtDesc(permitId);
    }

    /** 省域覆盖判定：许可不限省域、或作业省域未知、或两者相等（大小写不敏感）均视为覆盖。 */
    private boolean coversProvince(DroneAirspacePermit permit, String province) {
        if (province == null || province.isBlank()) {
            return true;
        }
        String scope = permit.getScopeProvince();
        return scope == null || scope.isBlank() || scope.trim().equalsIgnoreCase(province.trim());
    }

    /** 取未删除许可，不存在则抛 404。 */
    private DroneAirspacePermit requirePermit(Long permitId) {
        if (permitId == null) {
            throw BizException.invalidParam("error.param.invalid", "permitId");
        }
        return permitRepository.findById(permitId)
                .filter(p -> !Boolean.TRUE.equals(p.getDeleted()))
                .orElseThrow(() -> BizException.of(40472, "error.drone.permit.not.found", permitId));
    }
}
