package com.claw.server.domain.manufacturer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ManufacturerRepository extends JpaRepository<Manufacturer, Long>,
        JpaSpecificationExecutor<Manufacturer> {
    java.util.Optional<Manufacturer> findByCode(String code);
}
