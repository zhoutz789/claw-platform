package com.claw.server.domain.commission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceSalesCommissionRuleRepository extends JpaRepository<DeviceSalesCommissionRule, Long> {

    List<DeviceSalesCommissionRule> findByManufacturerIdAndEnabledTrue(Long manufacturerId);

    List<DeviceSalesCommissionRule> findByProductIdAndEnabledTrue(Long productId);
}
