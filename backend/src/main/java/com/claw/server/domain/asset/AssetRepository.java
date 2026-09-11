package com.claw.server.domain.asset;

import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long>, JpaSpecificationExecutor<Asset> {
    Optional<Asset> findByAssetNo(String assetNo);

    Optional<Asset> findByQrCode(String qrCode);

    List<Asset> findByAssetTypeAndStatus(AssetType assetType, AssetStatus status);

    List<Asset> findByUserId(Long userId);

    /** 取用户（使用人）名下未删除资产，供 provider 接单前能力匹配（P0 任务大厅）。 */
    List<Asset> findByUserIdAndDeletedFalse(Long userId);

    /** 取用户「管理人 或 当前使用人」名下未删除资产（与 TaskService.accept 的归属口径保持一致）。 */
    @Query("SELECT a FROM Asset a WHERE a.deleted = false AND (a.ownerId = :userId OR a.userId = :userId)")
    List<Asset> findOwnedOrUsedBy(@Param("userId") Long userId);

    @Query("SELECT a.status, COUNT(a) FROM Asset a WHERE a.deleted = false GROUP BY a.status")
    List<Object[]> countGroupByStatus();

    /** 产品下所有设备（点 1/4：产品中心内联设备列表）。 */
    List<Asset> findByProductId(Long productId);

    /** 按订单项溯源资产（V38：资产→订单项反向查询）。 */
    List<Asset> findByOrderItemId(Long orderItemId);

    /**
     * 按使用模式取资产（Phase D 能源调度）：ENERGY_STORAGE 恒为 STORAGE，
     * 换电 BATTERY 被调度临时借调时也置为 STORAGE，一次查询即得全部可调度储能容量。
     */
    List<Asset> findByUsageMode(com.claw.server.common.enums.AssetUsageMode usageMode);
}
