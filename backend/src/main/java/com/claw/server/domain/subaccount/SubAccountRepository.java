package com.claw.server.domain.subaccount;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubAccountRepository extends JpaRepository<SubAccount, Long> {

    List<SubAccount> findByOwnerPrincipalTypeAndOwnerPrincipalIdOrderByCreatedAtDesc(
            String ownerPrincipalType, Long ownerPrincipalId);

    List<SubAccount> findByOwnerPrincipalIdAndOwnerPrincipalTypeAndStatus(
            Long ownerPrincipalId, String ownerPrincipalType, String status);

    /** 子账号主体回溯的主入口（PrincipalResolver / PermissionService 用）。 */
    Optional<SubAccount> findTopByUserIdAndStatus(Long userId, String status);

    Optional<SubAccount> findByOwnerPrincipalTypeAndOwnerPrincipalIdAndUserId(
            String ownerPrincipalType, Long ownerPrincipalId, Long userId);

    boolean existsByOwnerPrincipalTypeAndOwnerPrincipalIdAndUserId(
            String ownerPrincipalType, Long ownerPrincipalId, Long userId);
}
