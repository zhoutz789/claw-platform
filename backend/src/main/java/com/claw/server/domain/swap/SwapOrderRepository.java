package com.claw.server.domain.swap;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SwapOrderRepository extends JpaRepository<SwapOrder, Long> {
    Optional<SwapOrder> findByOrderNo(String orderNo);

    List<SwapOrder> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<SwapOrder> findByStationIdAndStatus(Long stationId, String status);

    List<SwapOrder> findByStationIdAndCreatedAtBetween(Long stationId, java.time.Instant from, java.time.Instant to);
}
