package com.claw.server.domain.jurisdiction;

import com.claw.server.common.enums.JurisdictionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CountryRepository extends JpaRepository<Country, String> {
    Optional<Country> findByCode(String code);

    List<Country> findByStatus(JurisdictionStatus status);

    List<Country> findByRegionOrderByPilotOrderAsc(String region);
}
