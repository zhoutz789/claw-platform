package com.claw.server.domain.fulfillment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FulfillmentSettlementRepository extends JpaRepository<FulfillmentSettlement, Long> {

    Optional<FulfillmentSettlement> findByFulfillmentOrderId(Long fulfillmentOrderId);

    List<FulfillmentSettlement> findByStatus(com.claw.server.common.enums.SettlementStatus status);
}
