package com.claw.server.domain.payload;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DroneMissionRepository extends JpaRepository<DroneMission, Long> {

    /** 按无人机资产查作业计量（切片 4a 收益报告：任务侧 subtype 归类用）。 */
    List<DroneMission> findByAssetId(Long assetId);
}
