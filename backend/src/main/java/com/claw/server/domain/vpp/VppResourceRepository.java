package com.claw.server.domain.vpp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VppResourceRepository extends JpaRepository<VppResource, Long> {

    /** 资产唯一性由 uk_vpp_resources_asset 保证，此处最多一条。 */
    Optional<VppResource> findByAssetId(Long assetId);

    List<VppResource> findByPortfolioId(Long portfolioId);

    List<VppResource> findByPortfolioIdAndStatus(Long portfolioId, String status);

    long countByPortfolioId(Long portfolioId);
}
