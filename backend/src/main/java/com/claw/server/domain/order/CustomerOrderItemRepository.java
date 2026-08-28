package com.claw.server.domain.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerOrderItemRepository extends JpaRepository<CustomerOrderItem, Long> {

    List<CustomerOrderItem> findByOrderId(Long orderId);
}
