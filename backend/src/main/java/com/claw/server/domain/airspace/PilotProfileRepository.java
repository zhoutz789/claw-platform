package com.claw.server.domain.airspace;

import com.claw.server.common.enums.PilotStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 飞手档案仓储（对应 claw.pilot_profile）。
 */
public interface PilotProfileRepository extends JpaRepository<PilotProfile, Long> {

    /** 按平台用户取档案（user_id 唯一）。 */
    Optional<PilotProfile> findByUserId(Long userId);

    /** 按平台用户取未删除档案。 */
    Optional<PilotProfile> findByUserIdAndDeletedFalse(Long userId);

    /** 是否已建档（登记前置预检，避免撞唯一约束 → 500）。 */
    boolean existsByUserId(Long userId);

    /** 按审核状态列档案（status 为枚举，派生查询参数须传枚举而非 String）。 */
    List<PilotProfile> findByStatusAndDeletedFalse(PilotStatus status);

    /** 全部未删除档案。 */
    List<PilotProfile> findByDeletedFalse();
}
