package com.claw.server.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OnboardingApplicationLogRepository extends JpaRepository<OnboardingApplicationLog, Long> {

    List<OnboardingApplicationLog> findByApplicationIdOrderByCreatedAtDescIdDesc(Long applicationId);
}
