package com.claw.server.domain.autonomy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AutonomyTaskRepository extends JpaRepository<AutonomyTask, Long> {

    List<AutonomyTask> findByAssetId(Long assetId);
}
