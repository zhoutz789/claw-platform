package com.claw.server.domain.airspace;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FlightPlanRepository extends JpaRepository<FlightPlan, Long> {
}
