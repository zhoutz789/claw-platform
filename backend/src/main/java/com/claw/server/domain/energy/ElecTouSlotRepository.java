package com.claw.server.domain.energy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ElecTouSlotRepository extends JpaRepository<ElecTouSlot, Long> {

    List<ElecTouSlot> findByEnabledTrueOrderByPriorityAscStartMinuteAsc();
}
