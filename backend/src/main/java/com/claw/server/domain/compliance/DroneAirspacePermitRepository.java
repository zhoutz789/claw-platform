package com.claw.server.domain.compliance;

import com.claw.server.common.enums.PermitStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 无人机空域许可仓储（对应 claw.drone_airspace_permits）。
 */
public interface DroneAirspacePermitRepository extends JpaRepository<DroneAirspacePermit, Long> {

    /** 许可证号是否已存在（签发前置预检，避免撞唯一索引 → 500）。 */
    boolean existsByPermitNo(String permitNo);

    /** 某资产指定状态的许可（PermitGate 取 ACTIVE 候选）。 */
    List<DroneAirspacePermit> findByAssetIdAndStatusAndDeletedFalse(Long assetId, PermitStatus status);

    /** 某资产全部未删除许可（管理端列表）。 */
    List<DroneAirspacePermit> findByAssetIdAndDeletedFalseOrderByCreatedAtDesc(Long assetId);

    /** 全部未删除许可（管理端列表，按签发时间倒序）。 */
    List<DroneAirspacePermit> findByDeletedFalseOrderByCreatedAtDesc();
}
