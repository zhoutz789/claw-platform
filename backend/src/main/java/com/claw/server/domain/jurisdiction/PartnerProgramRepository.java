package com.claw.server.domain.jurisdiction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartnerProgramRepository extends JpaRepository<PartnerProgram, Long> {
    List<PartnerProgram> findByCountryCodeAndActiveTrueOrderByPartnerTypeAsc(String countryCode);
}
