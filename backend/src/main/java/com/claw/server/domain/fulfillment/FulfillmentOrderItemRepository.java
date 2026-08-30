package com.claw.server.domain.fulfillment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FulfillmentOrderItemRepository extends JpaRepository<FulfillmentOrderItem, Long> {

    List<FulfillmentOrderItem> findByFulfillmentOrderId(Long fulfillmentOrderId);

    List<FulfillmentOrderItem> findByDeviceId(Long deviceId);
}
