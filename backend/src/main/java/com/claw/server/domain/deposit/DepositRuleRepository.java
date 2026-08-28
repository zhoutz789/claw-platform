package com.claw.server.domain.deposit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DepositRuleRepository extends JpaRepository<DepositRule, Long> {

    Optional<DepositRule> findByAssetTypeAndYearIndex(String assetType, int yearIndex);
}
