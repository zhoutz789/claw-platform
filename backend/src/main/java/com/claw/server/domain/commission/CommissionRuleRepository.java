package com.claw.server.domain.commission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CommissionRuleRepository extends JpaRepository<CommissionRule, Long> {

    List<CommissionRule> findByManufacturerIdAndEnabledTrue(Long manufacturerId);

    List<CommissionRule> findByProductIdAndEnabledTrue(Long productId);

    List<CommissionRule> findByManufacturerIdIsNullAndEnabledTrue();

    Optional<CommissionRule> findByManufacturerIdAndProductIdAndEnabledTrue(Long manufacturerId, Long productId);
}
