package com.claw.server.domain.sharedpool;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RentalUsageSessionRepository extends JpaRepository<RentalUsageSession, Long> {

    List<RentalUsageSession> findByRentalOrderIdAndDeletedFalse(Long rentalOrderId);
}
