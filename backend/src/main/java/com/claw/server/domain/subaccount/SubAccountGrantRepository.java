package com.claw.server.domain.subaccount;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubAccountGrantRepository extends JpaRepository<SubAccountGrant, Long> {

    Optional<SubAccountGrant> findBySubAccountIdAndStatus(Long subAccountId, String status);

    Optional<SubAccountGrant> findTopBySubAccountIdOrderByIdDesc(Long subAccountId);
}
