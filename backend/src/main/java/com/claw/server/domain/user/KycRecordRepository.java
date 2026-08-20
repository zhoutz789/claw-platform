package com.claw.server.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KycRecordRepository extends JpaRepository<KycRecord, Long> {
    List<KycRecord> findByUserId(Long userId);

    Optional<KycRecord> findTopByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);
}
