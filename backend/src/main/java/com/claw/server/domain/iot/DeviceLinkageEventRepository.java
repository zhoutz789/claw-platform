package com.claw.server.domain.iot;

import com.claw.server.common.enums.LinkageDirection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceLinkageEventRepository extends JpaRepository<DeviceLinkageEvent, Long> {

    List<DeviceLinkageEvent> findByAssetIdOrderByTriggeredAtDesc(Long assetId);

    List<DeviceLinkageEvent> findByAssetIdAndDirectionOrderByTriggeredAtDesc(Long assetId, LinkageDirection direction);
}
