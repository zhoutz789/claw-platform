package com.claw.server.domain.jurisdiction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    Optional<Tenant> findByCountryCode(String countryCode);

    List<Tenant> findAll();
}
