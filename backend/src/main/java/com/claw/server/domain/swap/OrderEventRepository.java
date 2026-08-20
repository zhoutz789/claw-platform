package com.claw.server.domain.swap;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {
    List<OrderEvent> findByOrderNoOrderByIdAsc(String orderNo);
}
