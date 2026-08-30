package com.claw.server.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OnboardingApplicationRepository extends JpaRepository<OnboardingApplication, Long> {

    Optional<OnboardingApplication> findByApplicationNo(String applicationNo);

    List<OnboardingApplication> findByApplicantUserIdOrderByCreatedAtDesc(Long applicantUserId);

    Optional<OnboardingApplication> findByApplicantUserIdAndApplicantTypeAndStatus(
            Long applicantUserId, String applicantType, String status);

    List<OnboardingApplication> findByApplicantUserIdAndApplicantTypeOrderByCreatedAtDesc(
            Long applicantUserId, String applicantType);

    List<OnboardingApplication> findByApplicantTypeAndStatusOrderByCreatedAtDesc(
            String applicantType, String status);

    List<OnboardingApplication> findByStatusOrderByCreatedAtDesc(String status);

    Optional<OnboardingApplication> findByApplicantTypeAndPrincipalId(String applicantType, Long principalId);

    /** 缴款超时扫描用：找出已到 expire_at 但仍在待缴款状态的申请单。 */
    List<OnboardingApplication> findByStatusAndExpireAtBefore(String status, java.time.Instant now);
}
