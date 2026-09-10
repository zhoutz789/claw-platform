package com.claw.server.domain.camera;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface VideoSegmentRepository extends JpaRepository<VideoSegment, Long> {
    List<VideoSegment> findByStreamIdAndStartTsBetween(Long streamId, Instant from, Instant to);
}
