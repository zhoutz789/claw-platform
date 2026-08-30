package com.claw.server.domain.lifecycle;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LifecycleEventRepository extends JpaRepository<LifecycleEvent, Long> {
    List<LifecycleEvent> findByDeviceIdOrderByOccurredAtDesc(Long deviceId);
}
