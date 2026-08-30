package com.claw.server.domain.role;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RoleTemplateRepository extends JpaRepository<RoleTemplate, Long> {
    Optional<RoleTemplate> findByCode(String code);
}
