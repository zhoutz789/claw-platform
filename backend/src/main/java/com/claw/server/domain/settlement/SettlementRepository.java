package com.claw.server.domain.settlement;

import com.claw.server.common.enums.SettleStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    List<Settlement> findByBizRefTypeAndBizRefId(String bizRefType, Long bizRefId);

    Optional<Settlement> findByBizRefTypeAndBizRefIdAndSettleStep(String bizRefType, Long bizRefId, SettleStep settleStep);
}
