package com.claw.server.domain.asset;

import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long> {
    Optional<Asset> findByAssetNo(String assetNo);

    Optional<Asset> findByQrCode(String qrCode);

    List<Asset> findByAssetTypeAndStatus(AssetType assetType, AssetStatus status);

    List<Asset> findByUserId(Long userId);
}
