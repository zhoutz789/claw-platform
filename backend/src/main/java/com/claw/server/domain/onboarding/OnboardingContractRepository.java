package com.claw.server.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OnboardingContractRepository extends JpaRepository<OnboardingContract, Long> {

    List<OnboardingContract> findByApplicantTypeAndLangOrderByCreatedAtDesc(String applicantType, String lang);

    Optional<OnboardingContract> findByApplicantTypeAndLangAndStatus(String applicantType, String lang, String status);

    Optional<OnboardingContract> findByApplicantTypeAndLangAndVersion(String applicantType, String lang, String version);

    List<OnboardingContract> findByStatus(String status);
}
