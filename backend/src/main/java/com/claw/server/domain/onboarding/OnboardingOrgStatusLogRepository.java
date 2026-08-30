package com.claw.server.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OnboardingOrgStatusLogRepository extends JpaRepository<OnboardingOrgStatusLog, Long> {

    List<OnboardingOrgStatusLog> findByPrincipalTypeAndPrincipalIdOrderByCreatedAtDesc(
            String principalType, Long principalId);
}
