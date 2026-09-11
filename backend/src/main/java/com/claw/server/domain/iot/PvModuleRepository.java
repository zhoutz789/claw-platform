package com.claw.server.domain.iot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PvModuleRepository extends JpaRepository<PvModule, Long> {

    Optional<PvModule> findBySerialNo(String serialNo);

    List<PvModule> findByBatchNoOrderBySerialNoAsc(String batchNo);

    List<PvModule> findByStationAssetIdOrderByStringIdAscPositionAsc(Long stationAssetId);
}
