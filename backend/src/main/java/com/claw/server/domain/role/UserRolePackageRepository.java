package com.claw.server.domain.role;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRolePackageRepository extends JpaRepository<UserRolePackage, Long> {
    List<UserRolePackage> findByUserId(Long userId);

    Optional<UserRolePackage> findByUserIdAndRoleId(Long userId, Long roleId);

    boolean existsByUserIdAndRoleId(Long userId, Long roleId);
}
