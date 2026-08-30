package com.claw.server.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OnboardingMaterialRequirementRepository extends JpaRepository<OnboardingMaterialRequirement, Long> {

    List<OnboardingMaterialRequirement> findByApplicantTypeAndEnabledTrueOrderBySortNoAsc(String applicantType);

    List<OnboardingMaterialRequirement> findByApplicantTypeOrderBySortNoAsc(String applicantType);

    Optional<OnboardingMaterialRequirement> findByApplicantTypeAndMaterialCode(String applicantType, String materialCode);
}
