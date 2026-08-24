package com.claw.server.domain.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InsuranceFundRepository extends JpaRepository<InsuranceFund, Long> {

    Optional<InsuranceFund> findFirstByDeletedFalse();
}
