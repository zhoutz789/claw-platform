package com.claw.server.domain.jurisdiction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IdentityProviderRepository extends JpaRepository<IdentityProvider, Long> {
    List<IdentityProvider> findByCountryCodeAndActiveTrueOrderByPriorityAsc(String countryCode);

    Optional<IdentityProvider> findByCountryCodeAndProviderCodeAndActiveTrue(String countryCode, String providerCode);
}
