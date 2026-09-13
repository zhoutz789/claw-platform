package com.claw.server.domain.clearing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 分账规则仓储（claw.settlement_rule）。
 */
public interface SettlementRuleRepository extends JpaRepository<SettlementRule, Long> {

    /** 命中某场景的全部生效规则，按优先级升序（越小越先扣）。 */
    List<SettlementRule> findByBizSceneAndStatusAndDeletedFalseOrderByPriorityAsc(String bizScene, String status);

    /** 命中某场景 + 特定厂家的生效规则（优先于通用规则）。 */
    List<SettlementRule> findByBizSceneAndManufacturerIdAndStatusAndDeletedFalseOrderByPriorityAsc(
            String bizScene, Long manufacturerId, String status);

    List<SettlementRule> findByBizSceneAndDeletedFalse(String bizScene);

    Optional<SettlementRule> findByBizSceneAndPayeeTypeAndRuleVersionAndDeletedFalse(
            String bizScene, String payeeType, Integer ruleVersion);
}
