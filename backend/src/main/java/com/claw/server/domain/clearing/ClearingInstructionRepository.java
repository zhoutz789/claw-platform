package com.claw.server.domain.clearing;

import com.claw.server.common.enums.ClearingScene;
import com.claw.server.common.enums.ClearingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 清分指令仓储（claw.clearing_instruction）。
 */
public interface ClearingInstructionRepository extends JpaRepository<ClearingInstruction, Long> {

    Optional<ClearingInstruction> findByInstructionNo(String instructionNo);

    /** 业务幂等键查询（幂等重放）。 */
    Optional<ClearingInstruction> findByIdemKey(String idemKey);

    List<ClearingInstruction> findByBasisRef(String basisRef);

    List<ClearingInstruction> findByStatusOrderByCreatedAtAsc(ClearingStatus status);

    /** 批次汇总：按场景 + 状态过滤（设计 §6.2）。 */
    List<ClearingInstruction> findBySceneAndStatusOrderByCreatedAtAsc(ClearingScene scene, ClearingStatus status);

    /** 后台指令列表：按场景过滤（设计 §6.1）。 */
    List<ClearingInstruction> findBySceneOrderByCreatedAtAsc(ClearingScene scene);
}
