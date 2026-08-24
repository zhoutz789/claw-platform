package com.claw.server.domain.operator;

import com.claw.server.common.enums.OperatorAccountType;
import com.claw.server.common.enums.OperatorAccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperatorAccountRepository extends JpaRepository<OperatorAccount, Long> {

    List<OperatorAccount> findByOperatorIdAndDeletedFalse(Long operatorId);

    Optional<OperatorAccount> findByOperatorIdAndStationIdAndAccountTypeAndDeletedFalse(
            Long operatorId, Long stationId, OperatorAccountType type);
}
