package com.claw.server.domain.airspace;

import com.claw.server.common.enums.DroneSafetyEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DroneSafetyEventRepository extends JpaRepository<DroneSafetyEvent, Long> {

    List<DroneSafetyEvent> findByAssetIdOrderByCreatedAtDesc(Long assetId);

    List<DroneSafetyEvent> findByAssetIdAndStatus(Long assetId, DroneSafetyEventStatus status);

    boolean existsByAssetIdAndStatus(Long assetId, DroneSafetyEventStatus status);

    /**
     * 取该资产最早触发的一条指定状态事件（FIFO 确定性语义）。
     *
     * <p>不要改用 {@code findByAssetIdAndStatus(...).stream().findFirst()}：
     * 那条查询没有 ORDER BY，命中哪一条完全由数据库返回顺序决定 —— 同一份数据
     * 在不同执行计划下可能解除不同的事件，前端「解锁的是哪一条」不可预期。
     */
    Optional<DroneSafetyEvent> findFirstByAssetIdAndStatusOrderByCreatedAtAsc(
            Long assetId, DroneSafetyEventStatus status);
}
