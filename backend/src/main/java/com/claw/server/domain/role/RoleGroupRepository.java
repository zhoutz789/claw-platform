package com.claw.server.domain.role;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RoleGroupRepository extends JpaRepository<RoleGroup, Long> {
    Optional<RoleGroup> findByCode(String code);
}
