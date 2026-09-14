package com.claw.server.domain.airspace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 飞手违规处罚仓储（对应 claw.pilot_penalty）。
 */
public interface PilotPenaltyRepository extends JpaRepository<PilotPenalty, Long> {

    /** 某飞手档案的处罚历史（按决策时间倒序）。 */
    List<PilotPenalty> findByPilotIdAndDeletedFalseOrderByDecidedAtDesc(Long pilotId);

    /** 某飞手档案的生效处罚数（风控视图）。 */
    long countByPilotIdAndStatusAndDeletedFalse(Long pilotId, String status);
}
