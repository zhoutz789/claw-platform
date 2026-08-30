package com.claw.server.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OnboardingDepositRepository extends JpaRepository<OnboardingDeposit, Long> {

    Optional<OnboardingDeposit> findByDepositNo(String depositNo);

    List<OnboardingDeposit> findByApplicationIdOrderByCreatedAtDesc(Long applicationId);

    Optional<OnboardingDeposit> findTopByApplicationIdAndStatusOrderByCreatedAtDesc(Long applicationId, String status);

    List<OnboardingDeposit> findByStatusOrderByCreatedAtDesc(String status);

    List<OnboardingDeposit> findByPrincipalTypeAndPrincipalIdOrderByCreatedAtDesc(String principalType, Long principalId);
}
