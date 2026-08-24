package com.claw.server.domain.risk;

import com.claw.server.common.enums.RiskMonitorStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StationRiskMonitorRepository extends JpaRepository<StationRiskMonitor, Long> {

    List<StationRiskMonitor> findByStationIdAndDeletedFalse(Long stationId);

    List<StationRiskMonitor> findByStatusInAndDeletedFalse(List<RiskMonitorStatus> statuses);
}
