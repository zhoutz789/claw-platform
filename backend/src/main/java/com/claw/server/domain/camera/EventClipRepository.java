package com.claw.server.domain.camera;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface EventClipRepository extends JpaRepository<EventClip, Long> {
    List<EventClip> findByStreamIdAndStartTsBetween(Long streamId, Instant from, Instant to);
}
