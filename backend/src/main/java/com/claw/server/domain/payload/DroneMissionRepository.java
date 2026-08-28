package com.claw.server.domain.payload;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DroneMissionRepository extends JpaRepository<DroneMission, Long> {
}
