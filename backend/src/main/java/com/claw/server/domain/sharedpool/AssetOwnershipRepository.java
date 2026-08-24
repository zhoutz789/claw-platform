package com.claw.server.domain.sharedpool;

import com.claw.server.common.enums.OwnershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssetOwnershipRepository extends JpaRepository<AssetOwnership, Long> {

    Optional<AssetOwnership> findByAssetIdAndDeletedFalseAndStatus(Long assetId, OwnershipStatus status);

    default Optional<AssetOwnership> findActiveOwnership(Long assetId) {
        return findByAssetIdAndDeletedFalseAndStatus(assetId, OwnershipStatus.ACTIVE);
    }
}
