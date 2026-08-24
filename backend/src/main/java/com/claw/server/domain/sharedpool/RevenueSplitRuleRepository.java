package com.claw.server.domain.sharedpool;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RevenueSplitRuleRepository extends JpaRepository<RevenueSplitRule, Long> {

    Optional<RevenueSplitRule> findFirstByAssetIdAndStatusAndDeletedFalseOrderByCreatedAtDesc(
            Long assetId, String status);
}
