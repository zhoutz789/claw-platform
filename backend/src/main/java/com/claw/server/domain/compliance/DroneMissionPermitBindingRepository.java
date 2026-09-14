package com.claw.server.domain.compliance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 任务/架次 ↔ 许可绑定仓储（对应 claw.drone_mission_permit_bindings）。
 */
public interface DroneMissionPermitBindingRepository
        extends JpaRepository<DroneMissionPermitBinding, Long> {

    /** 某任务的许可绑定（按绑定时间倒序）。 */
    List<DroneMissionPermitBinding> findByTaskIdAndDeletedFalseOrderByBoundAtDesc(Long taskId);

    /** 某架次的许可绑定。 */
    List<DroneMissionPermitBinding> findByDroneMissionIdAndDeletedFalseOrderByBoundAtDesc(Long droneMissionId);

    /** 某许可的全部绑定。 */
    List<DroneMissionPermitBinding> findByPermitIdAndDeletedFalseOrderByBoundAtDesc(Long permitId);
}
