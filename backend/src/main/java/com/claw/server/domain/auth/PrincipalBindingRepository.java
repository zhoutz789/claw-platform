package com.claw.server.domain.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrincipalBindingRepository extends JpaRepository<PrincipalBinding, Long> {

    Optional<PrincipalBinding> findByUserIdAndPrincipalType(Long userId, String principalType);

    List<PrincipalBinding> findByPrincipalTypeAndPrincipalId(String principalType, Long principalId);

    boolean existsByUserIdAndPrincipalType(Long userId, String principalType);
}
