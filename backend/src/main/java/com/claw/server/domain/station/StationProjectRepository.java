package com.claw.server.domain.station;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 服务站下项目仓储（模块四 · 项目层）。 */
public interface StationProjectRepository extends JpaRepository<StationProject, Long> {

    List<StationProject> findByStationIdOrderBySortNoAsc(Long stationId);

    List<StationProject> findByStationIdAndStatusOrderBySortNoAsc(Long stationId, String status);

    List<StationProject> findByStationIdIn(List<Long> stationIds);

    List<StationProject> findByParentId(Long parentId);
}
