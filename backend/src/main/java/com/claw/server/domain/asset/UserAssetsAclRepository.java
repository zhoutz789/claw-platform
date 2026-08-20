package com.claw.server.domain.asset;

import com.claw.server.common.enums.AclRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAssetsAclRepository extends JpaRepository<UserAssetsAcl, Long> {
    List<UserAssetsAcl> findByUserId(Long userId);

    List<UserAssetsAcl> findByAssetId(Long assetId);

    Optional<UserAssetsAcl> findByUserIdAndAssetIdAndRelation(Long userId, Long assetId, AclRelation relation);

    boolean existsByUserIdAndAssetIdAndRelation(Long userId, Long assetId, AclRelation relation);
}
