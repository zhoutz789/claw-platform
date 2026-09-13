package com.claw.server.domain.clearing;

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

    List<ClearingInstruction> findByStatusOrderByCreatedAtAsc(String status);
}
