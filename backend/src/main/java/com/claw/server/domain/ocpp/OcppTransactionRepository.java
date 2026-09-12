package com.claw.server.domain.ocpp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OcppTransactionRepository extends JpaRepository<OcppTransaction, Long> {
    List<OcppTransaction> findByStationId(String stationId);

    List<OcppTransaction> findByStationIdAndStatus(String stationId, String status);

    Optional<OcppTransaction> findByTransactionId(Integer transactionId);
}
