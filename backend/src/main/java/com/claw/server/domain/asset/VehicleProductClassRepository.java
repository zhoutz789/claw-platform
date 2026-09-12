package com.claw.server.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VehicleProductClassRepository extends JpaRepository<VehicleProductClass, Long> {
    Optional<VehicleProductClass> findByCode(String code);
}
