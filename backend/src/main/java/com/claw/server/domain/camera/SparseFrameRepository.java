package com.claw.server.domain.camera;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface SparseFrameRepository extends JpaRepository<SparseFrame, Long> {
    List<SparseFrame> findByStreamIdAndTsBetween(Long streamId, Instant from, Instant to);
}
