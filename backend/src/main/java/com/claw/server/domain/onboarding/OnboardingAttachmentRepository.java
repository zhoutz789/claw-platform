package com.claw.server.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OnboardingAttachmentRepository extends JpaRepository<OnboardingAttachment, Long> {

    List<OnboardingAttachment> findByApplicationIdOrderByAttachTypeAscSortNoAsc(Long applicationId);

    List<OnboardingAttachment> findByApplicationIdAndAttachTypeOrderBySortNoAsc(Long applicationId, String attachType);

    Optional<OnboardingAttachment> findTopByApplicationIdAndAttachTypeAndReviewStatusOrderBySortNoAsc(
            Long applicationId, String attachType, String reviewStatus);

    void deleteByApplicationId(Long applicationId);
}
