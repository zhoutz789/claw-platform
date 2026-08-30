package com.claw.server.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OnboardingDepositTierRepository extends JpaRepository<OnboardingDepositTier, Long> {

    List<OnboardingDepositTier> findByApplicantTypeOrderBySortNoAsc(String applicantType);

    Optional<OnboardingDepositTier> findByApplicantTypeAndTierCode(String applicantType, String tierCode);

    List<OnboardingDepositTier> findByApplicantTypeAndEnabledTrueOrderBySortNoAsc(String applicantType);
}
