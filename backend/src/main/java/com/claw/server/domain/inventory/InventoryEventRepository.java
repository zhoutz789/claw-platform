package com.claw.server.domain.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryEventRepository extends JpaRepository<InventoryEvent, Long> {

    List<InventoryEvent> findByAssetIdOrderByOccurredAtDesc(Long assetId);
}
