package com.claw.server.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OnboardingCreditBlockRepository extends JpaRepository<OnboardingCreditBlock, Long> {

    List<OnboardingCreditBlock> findByPrincipalTypeAndPrincipalIdOrderByCreatedAtDesc(
            String principalType, Long principalId);
}
