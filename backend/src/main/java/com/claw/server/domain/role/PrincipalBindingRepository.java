package com.claw.server.domain.role;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PrincipalBindingRepository extends JpaRepository<PrincipalBinding, Long> {
    List<PrincipalBinding> findByUserId(Long userId);

    Optional<PrincipalBinding> findByUserIdAndPrincipalType(Long userId, String principalType);

    boolean existsByUserIdAndPrincipalType(Long userId, String principalType);

    List<PrincipalBinding> findByPrincipalTypeAndPrincipalId(String principalType, Long principalId);
}
