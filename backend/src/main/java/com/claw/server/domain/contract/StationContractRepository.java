package com.claw.server.domain.contract;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StationContractRepository extends JpaRepository<StationContract, Long> {

    /** 该站当前进行中（生效/已申请退出）的合约，用于激活幂等校验。 */
    Optional<StationContract> findByStationIdAndStatusAndDeletedFalse(Long stationId,
                                                                      com.claw.server.common.enums.ContractStatus status);

    List<StationContract> findByStationIdAndDeletedFalseOrderByCreatedAtDesc(Long stationId);

    /** 到期待处理（生效中且已过期），供定时任务置 EXPIRED。 */
    List<StationContract> findByStatusAndEffectiveToBeforeAndDeletedFalse(
            com.claw.server.common.enums.ContractStatus status, java.time.Instant effectiveTo);
}
