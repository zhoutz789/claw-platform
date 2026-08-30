package com.claw.server.domain.production;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductionTaskRepository extends JpaRepository<ProductionTask, Long> {
    List<ProductionTask> findByManufacturerId(Long manufacturerId);
}
