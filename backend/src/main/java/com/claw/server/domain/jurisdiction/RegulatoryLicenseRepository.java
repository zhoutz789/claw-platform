package com.claw.server.domain.jurisdiction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RegulatoryLicenseRepository extends JpaRepository<RegulatoryLicense, Long> {
    List<RegulatoryLicense> findByCountryCodeOrderByLicenseTypeAsc(String countryCode);
}
